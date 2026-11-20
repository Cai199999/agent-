#!/usr/bin/env python3
"""聊天写工具确认链路冒烟测试。

流程：对话触发写工具 → 得到 PENDING 工具调用 → 调用确认接口 → 校验业务状态。
"""

from __future__ import annotations

import json
import pathlib
import sys
import uuid

import httpx

ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = "http://127.0.0.1:8200"


def env() -> dict[str, str]:
    values: dict[str, str] = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def chat(client: httpx.Client, token: str, session_id: str, content: str) -> dict[str, list[dict]]:
    events: dict[str, list[dict]] = {}
    payload = {"clientMessageId": f"write-smoke-{uuid.uuid4().hex}",
               "content": content, "contentType": "TEXT"}
    headers = {"Authorization": f"Bearer {token}", "Accept": "text/event-stream",
               "Content-Type": "application/json"}
    event_name, data_lines = "", []
    with client.stream("POST", f"{BASE}/api/v1/chat/sessions/{session_id}/messages",
                       headers=headers, json=payload, timeout=120.0) as response:
        response.raise_for_status()
        for line in response.iter_lines():
            if line.startswith("event:"):
                event_name = line[6:].strip()
            elif line.startswith("data:"):
                data_lines.append(line[5:].strip())
            elif line.strip() == "":
                if event_name and data_lines:
                    try:
                        data = json.loads("\n".join(data_lines))
                    except json.JSONDecodeError:
                        data = {"raw": "\n".join(data_lines)}
                    events.setdefault(event_name, []).append(data)
                event_name, data_lines = "", []
    return events


def find_ticket(client: httpx.Client, token: str, ticket_no: str) -> dict:
    body = client.get(f"{BASE}/api/v1/tickets",
                      params={"keyword": ticket_no, "page": 1, "size": 10},
                      headers={"Authorization": f"Bearer {token}"}).json()
    items = (body.get("data") or {}).get("items") or []
    return next((item for item in items if item.get("ticketNo") == ticket_no), {})


def matches(key: str, actual, expected) -> bool:
    if actual == expected:
        return True
    return str(actual) == str(expected)


def run_case(client: httpx.Client, token: str, label: str, prompt: str,
             tool_name: str, ticket_no: str, expect: dict) -> bool:
    current = find_ticket(client, token, ticket_no)
    if expect and all(matches(key, current.get(key), value) for key, value in expect.items()):
        print(f"SKIP {label}: 已处于目标状态 { {k: current.get(k) for k in expect} }")
        return True
    session = client.post(f"{BASE}/api/v1/chat/sessions",
                          headers={"Authorization": f"Bearer {token}"}, json={}).json()
    session_id = (session.get("data") or {}).get("id")
    if session_id is None:
        print(f"FAIL {label}: 创建会话失败 {session}")
        return False
    events = chat(client, token, str(session_id), prompt)
    calls = [item for item in events.get("tool_call") or [] if item.get("name") == tool_name]
    if not calls:
        names = sorted({item.get("name") for item in events.get("tool_call") or []})
        print(f"FAIL {label}: 未调用 {tool_name}，实际={names}")
        return False
    call = calls[-1]
    if call.get("status") != "PENDING" or not call.get("requiresConfirm"):
        print(f"FAIL {label}: 工具状态不是待确认 {call}")
        return False
    confirmed = client.post(f"{BASE}/api/v1/chat/tool-calls/{call['id']}/confirm",
                            headers={"Authorization": f"Bearer {token}"}, json={})
    body = confirmed.json()
    if confirmed.status_code != 200 or body.get("code") != 0:
        print(f"FAIL {label}: 确认失败 http={confirmed.status_code} {body}")
        return False
    ticket = find_ticket(client, token, ticket_no)
    actual = {key: ticket.get(key) for key in expect}
    ok = all(matches(key, ticket.get(key), value) for key, value in expect.items())
    print(f"{'PASS' if ok else 'FAIL'} {label}: call={call['id']} actual={actual}")
    return ok


def main() -> int:
    values = env()
    with httpx.Client(timeout=30.0) as client:
        login = client.post(f"{BASE}/api/v1/auth/login",
                            json={"username": "admin",
                                  "password": values["DEV_ADMIN_PASSWORD"]}).json()
        token = (login.get("data") or {}).get("accessToken")
        if not token:
            print("login failed", login)
            return 1
        results = [
            run_case(client, token, "assign", "请调用 ticket_assign 工具，把工单 "
                     "GD20261119000108 派给用户 10002（张工）。只生成确认草稿，不要直接执行。",
                     "ticket_assign", "GD20261119000108",
                     {"statusCode": "PENDING_ACCEPT", "assigneeId": 10002}),
            run_case(client, token, "hold", "请调用 ticket_hold 工具，把工单 "
                     "GD20261118000082 挂起，原因：等待厂家发配件。只生成确认草稿。",
                     "ticket_hold", "GD20261118000082", {"statusCode": "SUSPENDED"}),
            run_case(client, token, "comment", "请调用 ticket_comment_add 工具，给工单 "
                     "GD20261119000106 添加公开评论：写工具确认测试。只生成确认草稿。",
                     "ticket_comment_add", "GD20261119000106", {}),
            run_case(client, token, "close", "请调用 ticket_close 工具，关闭工单 "
                     "GD20261119000107，结单原因 SOLVED。只生成确认草稿。",
                     "ticket_close", "GD20261119000107", {"statusCode": "CLOSED"}),
            run_case(client, token, "material", "请调用 ticket_material_add 工具，给工单 "
                     "GD20261119000106 登记材料：门锁配件，数量 1，单位 个，金额 100。只生成确认草稿。",
                     "ticket_material_add", "GD20261119000106", {}),
        ]
        # 恢复挂起工单，避免测试数据停留在 SUSPENDED。
        run_case(client, token, "resume", "请调用 ticket_resume 工具，恢复工单 "
                 "GD20261118000082，原因：配件已到。只生成确认草稿。",
                 "ticket_resume", "GD20261118000082", {"statusCode": "PROCESSING"})
        return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
