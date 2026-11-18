#!/usr/bin/env python3
"""AI 评测基线 v1（非门禁）。

运行：
    apps/python-ai/.venv/bin/python scripts/eval/run_eval.py
可选：
    ... run_eval.py --limit 5
"""

from __future__ import annotations

import json
import sys
import time
import uuid
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]
BASE = "http://127.0.0.1:8200/api/v1"
CASES = ROOT / "scripts" / "eval" / "eval_cases.json"
REPORT = ROOT / "docs" / "reports" / "AI评测基线v1.md"


def env() -> dict[str, str]:
    values: dict[str, str] = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def chat(client: httpx.Client, token: str, session_id: str, prompt: str) -> dict:
    events: dict[str, list[dict]] = {}
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "text/event-stream",
    }
    payload = {"clientMessageId": f"eval-{uuid.uuid4().hex}", "content": prompt}
    started = time.perf_counter()
    with client.stream("POST", f"{BASE}/chat/sessions/{session_id}/messages",
                       headers=headers, json=payload, timeout=120.0) as response:
        response.raise_for_status()
        event, data_lines = "", []
        for line in response.iter_lines():
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                data_lines.append(line[5:].strip())
            elif line.strip() == "":
                if event and data_lines:
                    try:
                        data = json.loads("\n".join(data_lines))
                    except json.JSONDecodeError:
                        data = {}
                    events.setdefault(event, []).append(data)
                event, data_lines = "", []
    return {"events": events, "latency_ms": int((time.perf_counter() - started) * 1000)}


def evaluate(case: dict, result: dict) -> tuple[bool, str]:
    events = result["events"]
    if events.get("error"):
        return False, f"error: {events['error'][0]}"
    if case["type"] == "knowledge":
        citations = events.get("citation", [])
        answer = "".join(item.get("content", "") for item in events.get("delta", []))
        keyword_ok = any(word in answer for word in case.get("keywords", []))
        ok = bool(citations) and (keyword_ok or not case.get("keywords"))
        return ok, f"citations={len(citations)} keyword={keyword_ok}"
    if case["type"] == "triage":
        triage = (events.get("triage") or [{}])[0]
        ok = triage.get("intent") == case["expect_intent"] and triage.get("action") == case["expect_action"]
        return ok, f"intent={triage.get('intent')} action={triage.get('action')}"
    tools = [item.get("name") for item in events.get("tool_call", [])]
    tool_ok = any(name in case.get("expect_tools", []) for name in tools)
    if case["type"] == "export":
        return tool_ok and bool(events.get("export_card")), f"tools={tools} export_card={bool(events.get('export_card'))}"
    return tool_ok, f"tools={tools}"


def write_report(rows: list[dict]) -> None:
    passed = sum(1 for row in rows if row["ok"])
    lines = [
        "# AI 评测基线 v1",
        "",
        "> 日期：2026-11-18",
        "> 类型：非门禁基线，用于观察趋势，不作为发布硬门禁。",
        f"> 样本：{len(rows)} 条",
        "",
        "## 结果",
        "",
        "| 用例 | 类型 | 结果 | 详情 | 延迟 |",
        "|---|---|---|---|---|",
    ]
    for row in rows:
        lines.append(
            f"| {row['id']} | {row['type']} | {'PASS' if row['ok'] else 'FAIL'} | "
            f"{row['detail']} | {row['latency_ms']}ms |"
        )
    lines += [
        "",
        "## 汇总",
        "",
        f"- 通过：{passed}/{len(rows)}",
        f"- 通过率：{passed / len(rows) * 100:.1f}%" if rows else "- 无样本",
        "",
        "## 说明",
        "",
        "- 知识用例以“有引用”为主，关键词命中为辅；",
        "- 分诊用例以 intent/action 为主；",
        "- 工具用例以工具名出现为主，不要求参数完全一致；",
        "- 导出用例检查 `ticket_export` 与 `export_card`；",
        "- v1 只建立基线，不设置自动失败阈值。",
        "",
    ]
    REPORT.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    values = env()
    password = values.get("DEV_ADMIN_PASSWORD", "")
    username = values.get("DEV_ADMIN_USERNAME", "admin")
    if not password:
        print("缺少 DEV_ADMIN_PASSWORD", file=sys.stderr)
        return 1
    limit = None
    if "--limit" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--limit") + 1])
    cases = json.loads(CASES.read_text(encoding="utf-8"))
    if limit:
        cases = cases[:limit]

    rows: list[dict] = []
    with httpx.Client(timeout=30.0) as client:
        login = client.post(f"{BASE}/auth/login", json={"username": username, "password": password})
        body = login.json()
        token = (body.get("data") or {}).get("accessToken")
        if not token:
            print(f"登录失败: {body.get('message')}", file=sys.stderr)
            return 1
        for case in cases:
            session = client.post(f"{BASE}/chat/sessions",
                                  headers={"Authorization": f"Bearer {token}"}, json={})
            session_id = (session.json().get("data") or {}).get("id")
            if not session_id:
                rows.append({**case, "ok": False, "detail": "创建会话失败", "latency_ms": 0})
                continue
            try:
                result = chat(client, token, str(session_id), case["prompt"])
                ok, detail = evaluate(case, result)
                rows.append({**case, "ok": ok, "detail": detail,
                             "latency_ms": result["latency_ms"]})
                print(f"{'PASS' if ok else 'FAIL'} {case['id']} {detail}")
            except Exception as exc:  # noqa: BLE001
                rows.append({**case, "ok": False, "detail": str(exc), "latency_ms": 0})
                print(f"FAIL {case['id']} {exc}")
    write_report(rows)
    print(f"report={REPORT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
