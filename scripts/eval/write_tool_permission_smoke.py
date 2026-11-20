#!/usr/bin/env python3
"""写工具权限矩阵冒烟：engineer1 / reporter1 的正向与拒绝路径。

运行前需 Java BFF + Python AI 在 8200/8300。
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


def login(client: httpx.Client, username: str, password: str) -> str:
    body = client.post(f"{BASE}/api/v1/auth/login",
                       json={"username": username, "password": password}).json()
    token = (body.get("data") or {}).get("accessToken")
    if not token:
        raise RuntimeError(f"login failed: {username} {body}")
    return token


def chat(client: httpx.Client, token: str, session_id: str,
         content: str) -> dict[str, list[dict]]:
    events: dict[str, list[dict]] = {}
    payload = {"clientMessageId": f"perm-{uuid.uuid4().hex}",
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


def tool_call(client: httpx.Client, token: str, prompt: str, tool_name: str):
    session = client.post(f"{BASE}/api/v1/chat/sessions",
                          headers={"Authorization": f"Bearer {token}"}, json={}).json()
    session_id = (session.get("data") or {}).get("id")
    events = chat(client, token, str(session_id), prompt)
    calls = [item for item in events.get("tool_call") or [] if item.get("name") == tool_name]
    return calls[-1] if calls else None


def confirm(client: httpx.Client, token: str, call_id: str) -> tuple[int, dict]:
    response = client.post(f"{BASE}/api/v1/chat/tool-calls/{call_id}/confirm",
                           headers={"Authorization": f"Bearer {token}"}, json={})
    return response.status_code, response.json()


def create_ticket(client: httpx.Client, token: str, title: str) -> dict:
    body = client.post(f"{BASE}/api/v1/tickets", headers={
        "Authorization": f"Bearer {token}",
        "Idempotency-Key": f"perm-{uuid.uuid4().hex}"},
        json={"title": title, "description": "权限矩阵测试",
              "ticketTypeCode": "GENERAL", "priorityCode": "NORMAL",
              "locationText": "权限测试"}).json()
    return (body.get("data") or {}).get("ticket") or {}


def main() -> int:
    values = env()
    with httpx.Client(timeout=30.0) as client:
        admin = login(client, "admin", values["DEV_ADMIN_PASSWORD"])
        engineer = login(client, "engineer1", values["DEV_ADMIN_PASSWORD"])
        reporter = login(client, "reporter1", values["DEV_ADMIN_PASSWORD"])
        results = []

        # 新建一张工单并派给 engineer1，保证 accept 用例可重复执行。
        engineer_ticket = create_ticket(client, admin, "权限矩阵-工程师接单")
        assigned = client.post(
            f"{BASE}/api/v1/tickets/{engineer_ticket['id']}/assign",
            headers={"Authorization": f"Bearer {admin}"},
            json={"expectedVersion": engineer_ticket.get("version"), "assigneeId": 10002}).json()
        engineer_ticket_no = engineer_ticket.get("ticketNo")
        if assigned.get("code") != 0:
            print("assign setup failed", assigned)

        call = tool_call(client, engineer, "请调用 ticket_accept 工具接单 "
                         f"{engineer_ticket_no}。只生成确认草稿。", "ticket_accept")
        if call:
            status, body = confirm(client, engineer, call["id"])
            results.append(("engineer accept own ticket", status == 200 and body.get("code") == 0))
        else:
            results.append(("engineer accept own ticket", False))

        call = tool_call(client, engineer, "请调用 ticket_assign 工具，把工单 "
                         f"{engineer_ticket_no} 派给用户 10002。只生成确认草稿。", "ticket_assign")
        if call:
            status, body = confirm(client, engineer, call["id"])
            results.append(("engineer assign denied",
                            status == 403 and body.get("code") == 10003))
        else:
            results.append(("engineer assign denied", False))

        ticket = create_ticket(client, reporter, "权限矩阵-报障人权限")
        ticket_no = ticket.get("ticketNo")
        if ticket_no:
            call = tool_call(client, reporter, "请调用 ticket_close 工具关闭工单 "
                             f"{ticket_no}。只生成确认草稿。", "ticket_close")
            if call:
                status, body = confirm(client, reporter, call["id"])
                results.append(("reporter close denied",
                                status == 403 and body.get("code") == 10003))
            else:
                results.append(("reporter close denied", False))

            call = tool_call(client, reporter, "请调用 ticket_comment_add 工具，给工单 "
                             f"{ticket_no} 添加公开评论：权限矩阵评论。只生成确认草稿。",
                             "ticket_comment_add")
            if call:
                status, body = confirm(client, reporter, call["id"])
                results.append(("reporter comment own ticket",
                                status == 200 and body.get("code") == 0))
            else:
                results.append(("reporter comment own ticket", False))
        else:
            print("create ticket failed")
            results.extend([("reporter close denied", False),
                            ("reporter comment own ticket", False)])

    for name, ok in results:
        print(f"{'PASS' if ok else 'FAIL'} {name}")
    return 0 if all(ok for _, ok in results) else 1


if __name__ == "__main__":
    sys.exit(main())
