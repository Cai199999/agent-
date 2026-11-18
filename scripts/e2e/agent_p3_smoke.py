#!/usr/bin/env python3
"""Agent P3：附件上传 + 现场解决 + 任务事件 + Agent Card。"""

from __future__ import annotations

import sys
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]
BASE = "http://127.0.0.1:8200"
PNG = bytes.fromhex(
    "89504e470d0a1a0a0000000d4948445200000001000000010804000000"
    "b51c0c020000000b4944415478da63f8ff1f0003eb01f5697a9f490000"
    "000049454e44ae426082"
)
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
    }
    suffix = str(int(time.time()))
    with httpx.Client(timeout=60.0) as client:
        card = client.get(f"{BASE}/.well-known/agent-card.json")
        check("Agent Card", card.status_code == 200 and card.json().get("name"))

        def task(capability: str, input_data: dict, client_task_id: str) -> dict:
            response = client.post(f"{BASE}/agent/v1/tasks",
                                   headers={**headers, "Content-Type": "application/json"},
                                   json={"taskType": capability, "input": input_data,
                                         "clientTaskId": client_task_id})
            data = (response.json() if response.status_code == 200 else {}).get("data") or {}
            if data.get("status") == "WAITING_CONFIRM" and data.get("confirmationToken"):
                confirm = client.post(
                    f"{BASE}/agent/v1/tasks/{data['taskId']}/confirm",
                    headers={**headers, "Content-Type": "application/json"},
                    json={"confirmationToken": data["confirmationToken"]},
                )
                data = (confirm.json() if confirm.status_code == 200 else {}).get("data") or {}
            return data

        created = task("ticket.create", {
            "description": f"Agent P3 现场解决 {suffix}",
            "ticketTypeCode": "ELEVATOR",
            "locationType": "INDOOR",
            "locationText": "A栋2号梯",
            "buildingId": 101,
            "roomId": 1001,
        }, f"p3-create-{suffix}")
        ticket = (created.get("result") or {}).get("ticket") or {}
        ticket_id = ticket.get("id")
        check("ticket.create", created.get("status") == "SUCCEEDED" and bool(ticket_id),
              ticket.get("ticketNo", ""))

        if ticket_id:
            check("ticket.assign",
                  task("ticket.assign", {"ticketId": ticket_id, "assigneeId": 10001},
                       f"p3-assign-{suffix}").get("status") == "SUCCEEDED")
            check("ticket.accept",
                  task("ticket.accept", {"ticketId": ticket_id},
                       f"p3-accept-{suffix}").get("status") == "SUCCEEDED")

            upload = client.post(
                f"{BASE}/agent/v1/tickets/{ticket_id}/attachments",
                headers=headers,
                params={"type": "ONSITE"},
                files={"file": ("onsite.png", PNG, "image/png")},
            )
            check("attachment.upload", upload.status_code == 200 and upload.json().get("code") == 0)

            resolved = task("ticket.resolve", {
                "ticketId": ticket_id,
                "serviceItemText": "电梯门锁检修",
                "onsiteSituation": "现场已处理",
            }, f"p3-resolve-{suffix}")
            check("ticket.resolve", resolved.get("status") == "SUCCEEDED")
            task_id = resolved.get("taskId")
            if task_id:
                events = client.get(f"{BASE}/agent/v1/tasks/{task_id}/events", headers=headers)
                statuses = [item.get("status") for item in (events.json().get("data") or [])]
                check("task.events", events.status_code == 200 and "SUCCEEDED" in statuses,
                      str(statuses))

    failed = [item for item in RESULTS if not item[1]]
    print(f"\npassed={len(RESULTS) - len(failed)} failed={len(failed)} total={len(RESULTS)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
