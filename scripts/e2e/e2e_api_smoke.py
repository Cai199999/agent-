#!/usr/bin/env python3
"""E2E API 冒烟测试（半自动）。

覆盖：
- 登录、创建会话、对话分诊/草稿确认建单
- 工单详情、AI 分析
- 派单、接单、解决、验证、关闭
- 知识检索、下载

运行：
    apps/python-ai/.venv/bin/python scripts/e2e/e2e_api_smoke.py

说明：
- 需要 Web/Java/Python 三端已启动；
- 只读取项目根 .env 的 dev 账号密码，不打印密钥；
- 不修改共享中间件，只通过 Java BFF 调用业务 API。
"""

from __future__ import annotations

import json
import os
import sys
import uuid
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]
BASE = "http://127.0.0.1:8200/api/v1"
RESULTS: list[tuple[str, bool, str]] = []


def load_env() -> dict[str, str]:
    values: dict[str, str] = {}
    path = ROOT / ".env"
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


ENV = load_env()
ADMIN_USER = ENV.get("DEV_ADMIN_USERNAME", "admin")
PASSWORD = ENV.get("DEV_ADMIN_PASSWORD", "")
ENGINEER_USER = "engineer1"


def check(name: str, ok: bool, detail: str = "") -> None:
    RESULTS.append((name, ok, detail))
    print(f"{'PASS' if ok else 'FAIL'}  {name}{('  ' + detail) if detail else ''}")


def api(client: httpx.Client, method: str, path: str, token: str | None = None,
        **kwargs) -> tuple[int, dict]:
    headers = kwargs.pop("headers", {})
    if token:
        headers["Authorization"] = f"Bearer {token}"
    response = client.request(method, f"{BASE}{path}", headers=headers, **kwargs)
    try:
        body = response.json()
    except Exception:
        body = {"raw": response.text}
    return response.status_code, body


def login(client: httpx.Client, username: str) -> str | None:
    if not PASSWORD:
        check("读取 DEV_ADMIN_PASSWORD", False, "未在 .env 中找到")
        return None
    status, body = api(client, "POST", "/auth/login",
                       json={"username": username, "password": PASSWORD})
    ok = status == 200 and body.get("code") == 0 and body.get("data", {}).get("accessToken")
    check(f"登录 {username}", bool(ok), f"http={status}")
    return body.get("data", {}).get("accessToken") if ok else None


def create_session(client: httpx.Client, token: str) -> str | None:
    status, body = api(client, "POST", "/chat/sessions", token, json={})
    session_id = body.get("data", {}).get("id")
    check("创建 AI 会话", status == 200 and body.get("code") == 0 and session_id is not None,
          f"http={status}")
    return str(session_id) if session_id else None


def chat(client: httpx.Client, token: str, session_id: str, content: str) -> dict[str, list[dict]]:
    """发送一条消息并收集 SSE 事件。"""
    events: dict[str, list[dict]] = {}
    payload = {
        "clientMessageId": f"e2e-{uuid.uuid4().hex}",
        "content": content,
        "contentType": "TEXT",
    }
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "text/event-stream",
        "Content-Type": "application/json",
    }
    with client.stream("POST", f"{BASE}/chat/sessions/{session_id}/messages",
                       headers=headers, json=payload, timeout=90.0) as response:
        response.raise_for_status()
        event_name = ""
        data_lines: list[str] = []
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


def ticket_detail(client: httpx.Client, token: str, ticket_id: str) -> dict:
    _, body = api(client, "GET", f"/tickets/{ticket_id}", token)
    return body.get("data") or {}


def transition(client: httpx.Client, token: str, ticket_id: str, action: str,
               payload: dict) -> bool:
    version = ticket_detail(client, token, ticket_id).get("ticket", {}).get("version")
    if version is None:
        check(f"工单流转 {action}", False, "无法获取 version")
        return False
    body = {"expectedVersion": version, **payload}
    status, response = api(client, "POST", f"/tickets/{ticket_id}/{action}", token, json=body)
    ok = status == 200 and response.get("code") == 0
    check(f"工单流转 {action}", ok, f"http={status} message={response.get('message', '')}")
    return ok


def main() -> int:
    print(f"E2E smoke against {BASE}")
    with httpx.Client(timeout=30.0) as client:
        admin = login(client, ADMIN_USER)
        if not admin:
            return 1
        session_id = create_session(client, admin)
        if not session_id:
            return 1

        events = chat(client, admin, session_id, "A栋2号梯困人了，请尽快处理，并说明现场处置步骤。")
        check("对话返回 done", bool(events.get("done")), f"events={sorted(events)}")
        check("对话无 error", not events.get("error"), str(events.get("error", "")))
        check("AI 分诊事件", bool(events.get("triage")), str(events.get("triage", "")))

        ticket_id = None
        cards = events.get("ticket_card") or []
        if cards:
            card = cards[-1]
            triage_id = card.get("triageId")
            if triage_id:
                status, body = api(client, "POST", f"/chat/triage-records/{triage_id}/confirm",
                                   admin, json={})
                ticket = (body.get("data") or {}).get("ticket") or {}
                ticket_id = ticket.get("id")
                check("确认分诊草稿建单", status == 200 and body.get("code") == 0 and ticket_id,
                      f"http={status} ticket={ticket.get('ticketNo', '')}")
        if not ticket_id:
            status, body = api(client, "POST", "/tickets", admin,
                               headers={"Idempotency-Key": f"e2e-{uuid.uuid4().hex}"},
                               json={
                                   "title": "E2E 电梯困人",
                                   "description": "A栋2号梯困人，需现场处置",
                                   "ticketTypeCode": "ELEVATOR",
                                   "priorityCode": "URGENT",
                                   "locationText": "A栋 2号",
                               })
            ticket_id = (body.get("data") or {}).get("ticket", {}).get("id")
            check("兜底表单建单", status == 200 and body.get("code") == 0 and ticket_id,
                  f"http={status}")

        if ticket_id:
            detail = ticket_detail(client, admin, str(ticket_id))
            check("工单详情", bool(detail.get("ticket")), detail.get("ticket", {}).get("ticketNo", ""))

            status, body = api(client, "POST", f"/tickets/{ticket_id}/ai-analysis", admin, json={})
            analysis = body.get("data") or {}
            check("工单 AI 分析", status == 200 and body.get("code") == 0 and bool(analysis.get("summary")),
                  f"http={status} risk={analysis.get('riskLevel', '')}")

            _, users = api(client, "GET", "/users/options", admin)
            engineer = next((u for u in users.get("data", []) if u.get("username") == ENGINEER_USER), None)
            check("获取工程师选项", engineer is not None)
            if engineer:
                transition(client, admin, str(ticket_id), "assign",
                           {"assigneeId": int(engineer["id"])})

                engineer_token = login(client, ENGINEER_USER)
                if engineer_token:
                    transition(client, engineer_token, str(ticket_id), "accept", {})
                    transition(client, engineer_token, str(ticket_id), "resolve",
                               {"reason": "E2E：现场已恢复，门锁检查正常"})
                    transition(client, admin, str(ticket_id), "verify", {"approved": True})
                    transition(client, admin, str(ticket_id), "close",
                               {"closeReasonCode": "SOLVED"})

        status, body = api(client, "POST", "/knowledge/search", admin,
                           json={"query": "电梯困人怎么处理", "topK": 3})
        hits = body.get("data") or []
        check("知识检索", status == 200 and body.get("code") == 0 and bool(hits),
              f"http={status} hits={len(hits)}")
        if hits:
            doc_id = hits[0].get("docId")
            status, body = api(client, "GET", f"/knowledge/docs/{doc_id}/download", admin)
            check("知识下载", status == 200 and bool((body.get("data") or {}).get("url")),
                  f"http={status}")

    failed = [item for item in RESULTS if not item[1]]
    print("\n=== SUMMARY ===")
    print(f"passed={len(RESULTS) - len(failed)} failed={len(failed)} total={len(RESULTS)}")
    for name, ok, detail in failed:
        print(f"FAILED: {name} {detail}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
