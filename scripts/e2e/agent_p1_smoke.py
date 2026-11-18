#!/usr/bin/env python3
"""Agent P1：写能力 + 确认令牌 + 任务持久化冒烟测试。"""

from __future__ import annotations

import sys
import time
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
    headers = {
        "X-Internal-Token": values.get("INTERNAL_TOKEN_P2J_CURRENT", ""),
        "X-Actor-Id": "10001",
        "X-Actor-Roles": "ADMIN",
        "Content-Type": "application/json",
    }
    suffix = str(int(time.time()))
    with httpx.Client(timeout=60.0) as client:
        create = client.post(f"{BASE}/agent/v1/tasks", headers=headers, json={
            "taskType": "ticket.create",
            "input": {
                "description": f"Agent P1 冒烟工单 {suffix}：A栋2号梯异响",
                "ticketTypeCode": "ELEVATOR",
                "locationType": "INDOOR",
                "locationText": "A栋2号梯",
                "buildingId": 101,
                "roomId": 1001,
            },
            "clientTaskId": f"p1-smoke-create-{suffix}",
        })
        create_body = create.json() if create.status_code == 200 else {}
        create_data = create_body.get("data") or {}
        check("ticket.create WAITING_CONFIRM",
              create.status_code == 200 and create_data.get("status") == "WAITING_CONFIRM"
              and bool(create_data.get("confirmationToken")))
        task_id = create_data.get("taskId")
        token = create_data.get("confirmationToken")
        if task_id and token:
            confirm = client.post(f"{BASE}/agent/v1/tasks/{task_id}/confirm", headers=headers,
                                  json={"confirmationToken": token})
            confirm_body = confirm.json() if confirm.status_code == 200 else {}
            confirm_data = confirm_body.get("data") or {}
            ticket = (confirm_data.get("result") or {}).get("ticket") or {}
            check("ticket.create confirm", confirm.status_code == 200
                  and confirm_data.get("status") == "SUCCEEDED"
                  and bool(ticket.get("ticketNo")), ticket.get("ticketNo", ""))

        export = client.post(f"{BASE}/agent/v1/tasks", headers=headers, json={
            "taskType": "ticket.export",
            "input": {
                "startTime": "2026-11-01T00:00:00+08:00",
                "endTime": "2026-11-18T23:59:59+08:00",
            },
            "clientTaskId": f"p1-smoke-export-{suffix}",
        })
        export_body = export.json() if export.status_code == 200 else {}
        export_data = export_body.get("data") or {}
        check("ticket.export WAITING_CONFIRM",
              export.status_code == 200 and export_data.get("status") == "WAITING_CONFIRM"
              and bool(export_data.get("confirmationToken")))
        export_id = export_data.get("taskId")
        export_token = export_data.get("confirmationToken")
        if export_id and export_token:
            confirm = client.post(f"{BASE}/agent/v1/tasks/{export_id}/confirm", headers=headers,
                                  json={"confirmationToken": export_token})
            confirm_body = confirm.json() if confirm.status_code == 200 else {}
            confirm_data = confirm_body.get("data") or {}
            result = confirm_data.get("result") or {}
            check("ticket.export confirm", confirm.status_code == 200
                  and confirm_data.get("status") == "SUCCEEDED"
                  and bool(result.get("url")),
                  f"{result.get('filename')} rows={result.get('rowCount')}")

    failed = [item for item in RESULTS if not item[1]]
    print(f"\npassed={len(RESULTS) - len(failed)} failed={len(failed)} total={len(RESULTS)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
