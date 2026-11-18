#!/usr/bin/env python3
"""Agent P2：写能力生命周期 + 取消。"""

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
        def task(capability: str, input_data: dict, client_task_id: str) -> dict:
            response = client.post(f"{BASE}/agent/v1/tasks", headers=headers, json={
                "taskType": capability,
                "input": input_data,
                "clientTaskId": client_task_id,
            })
            data = (response.json() if response.status_code == 200 else {}).get("data") or {}
            if data.get("status") == "WAITING_CONFIRM" and data.get("confirmationToken"):
                confirm = client.post(
                    f"{BASE}/agent/v1/tasks/{data['taskId']}/confirm",
                    headers=headers,
                    json={"confirmationToken": data["confirmationToken"]},
                )
                data = (confirm.json() if confirm.status_code == 200 else {}).get("data") or {}
            return data

        created = task("ticket.create", {
            "description": f"Agent P2 生命周期 {suffix}：电梯门无法关闭",
            "ticketTypeCode": "ELEVATOR",
            "locationType": "INDOOR",
            "locationText": "A栋2号梯",
            "buildingId": 101,
            "roomId": 1001,
        }, f"p2-life-create-{suffix}")
        ticket = (created.get("result") or {}).get("ticket") or {}
        ticket_id = ticket.get("id")
        check("ticket.create", created.get("status") == "SUCCEEDED" and bool(ticket_id),
              ticket.get("ticketNo", ""))

        if ticket_id:
            assigned = task("ticket.assign", {"ticketId": ticket_id, "assigneeId": 10002},
                            f"p2-life-assign-{suffix}")
            check("ticket.assign", assigned.get("status") == "SUCCEEDED")
            accepted = task("ticket.accept", {"ticketId": ticket_id},
                            f"p2-life-accept-{suffix}")
            check("ticket.accept", accepted.get("status") == "SUCCEEDED")
            held = task("ticket.hold", {"ticketId": ticket_id, "reason": "P2 测试挂起"},
                        f"p2-life-hold-{suffix}")
            check("ticket.hold", held.get("status") == "SUCCEEDED")
            resumed = task("ticket.resume", {"ticketId": ticket_id, "reason": "P2 测试恢复"},
                           f"p2-life-resume-{suffix}")
            check("ticket.resume", resumed.get("status") == "SUCCEEDED")
            finished = task("ticket.finish", {"ticketId": ticket_id, "reason": "P2 测试结束"},
                            f"p2-life-finish-{suffix}")
            check("ticket.finish", finished.get("status") == "SUCCEEDED")

        pending = client.post(f"{BASE}/agent/v1/tasks", headers=headers, json={
            "taskType": "ticket.create",
            "input": {"description": f"P2 取消测试 {suffix}", "ticketTypeCode": "GENERAL"},
            "clientTaskId": f"p2-cancel-{suffix}",
        })
        pending_data = (pending.json().get("data") or {})
        cancelled = client.post(
            f"{BASE}/agent/v1/tasks/{pending_data.get('taskId')}/cancel", headers=headers)
        cancelled_data = (cancelled.json() if cancelled.status_code == 200 else {}).get("data") or {}
        check("ticket cancel", cancelled_data.get("status") == "CANCELLED")

    failed = [item for item in RESULTS if not item[1]]
    print(f"\npassed={len(RESULTS) - len(failed)} failed={len(failed)} total={len(RESULTS)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
