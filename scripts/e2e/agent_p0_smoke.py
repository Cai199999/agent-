#!/usr/bin/env python3
"""Agent P0（Manifest + 只读 Task API）冒烟测试。

运行：
    apps/python-ai/.venv/bin/python scripts/e2e/agent_p0_smoke.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]
BASE = "http://127.0.0.1:8200"
RESULTS: list[tuple[str, bool, str]] = []


def env() -> dict[str, str]:
    values: dict[str, str] = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def check(name: str, ok: bool, detail: str = "") -> None:
    RESULTS.append((name, ok, detail))
    print(f"{'PASS' if ok else 'FAIL'} {name} {detail}")


def main() -> int:
    values = env()
    token = values.get("INTERNAL_TOKEN_P2J_CURRENT", "")
    headers = {
        "X-Internal-Token": token,
        "X-Actor-Id": "10001",
        "X-Actor-Roles": "ADMIN",
        "Content-Type": "application/json",
    }
    with httpx.Client(timeout=30.0) as client:
        manifest = client.get(f"{BASE}/agent/v1/manifest", headers=headers)
        body = manifest.json() if manifest.status_code == 200 else {}
        caps = [item.get("name") for item in (body.get("data") or {}).get("capabilities", [])]
        check("Manifest", manifest.status_code == 200 and "ticket.query" in caps, str(caps))

        query = client.post(f"{BASE}/agent/v1/tasks", headers=headers, json={
            "taskType": "ticket.query",
            "input": {"limit": 3},
            "clientTaskId": "p0-smoke-query",
        })
        query_body = query.json() if query.status_code == 200 else {}
        query_data = query_body.get("data") or {}
        check("ticket.query", query.status_code == 200 and query_data.get("status") == "SUCCEEDED",
              f"count={len(query_data.get('result') or [])}")
        task_id = query_data.get("taskId")

        if task_id:
            got = client.get(f"{BASE}/agent/v1/tasks/{task_id}", headers=headers)
            got_body = got.json() if got.status_code == 200 else {}
            check("GET task", got.status_code == 200 and (got_body.get("data") or {}).get("status") == "SUCCEEDED")

        detail = client.post(f"{BASE}/agent/v1/tasks", headers=headers, json={
            "taskType": "ticket.detail",
            "input": {"ticketNo": "GD20261118000028"},
        })
        detail_body = detail.json() if detail.status_code == 200 else {}
        detail_data = detail_body.get("data") or {}
        check("ticket.detail by ticketNo", detail.status_code == 200
              and detail_data.get("status") == "SUCCEEDED"
              and (detail_data.get("result") or {}).get("ticket", {}).get("ticketNo") == "GD20261118000028")

        knowledge = client.post(f"{BASE}/agent/v1/tasks", headers=headers, json={
            "taskType": "knowledge.search",
            "input": {"query": "电梯困人", "topK": 2},
        })
        knowledge_body = knowledge.json() if knowledge.status_code == 200 else {}
        knowledge_data = knowledge_body.get("data") or {}
        check("knowledge.search", knowledge.status_code == 200
              and knowledge_data.get("status") == "SUCCEEDED"
              and bool(knowledge_data.get("result")))

        unknown = client.post(f"{BASE}/agent/v1/tasks", headers=headers, json={
            "taskType": "ticket.delete",
        })
        unknown_body = unknown.json() if unknown.status_code == 200 else {}
        unknown_data = unknown_body.get("data") or {}
        check("unknown capability rejected", unknown.status_code == 200
              and unknown_data.get("status") == "FAILED"
              and unknown_data.get("errorCode") == "60001")

    failed = [item for item in RESULTS if not item[1]]
    print(f"\npassed={len(RESULTS) - len(failed)} failed={len(failed)} total={len(RESULTS)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
