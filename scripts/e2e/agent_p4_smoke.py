#!/usr/bin/env python3
"""Agent P4：异步任务、SSE 事件流、委托令牌与异步写确认。"""

from __future__ import annotations

import sys
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]
BASE = "http://127.0.0.1:8200"
RESULTS: list[tuple[str, bool, str]] = []
PNG = bytes.fromhex(
    "89504e470d0a1a0a0000000d4948445200000001000000010804000000"
    "b51c0c020000000b4944415478da63f8ff1f0003eb01f5697a9f490000"
    "000049454e44ae426082"
)


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
    internal = {
        "X-Internal-Token": values.get("INTERNAL_TOKEN_P2J_CURRENT", ""),
        "X-Actor-Id": "10001",
        "X-Actor-Roles": "ADMIN",
        "Content-Type": "application/json",
    }
    suffix = str(int(time.time()))
    with httpx.Client(timeout=30.0) as client:
        manifest = client.get(f"{BASE}/agent/v1/manifest", headers=internal).json()["data"]
        check("manifest 0.8.0", manifest.get("version") == "0.8.0", str(manifest.get("version")))
        check("manifest async modes", "ASYNC" in manifest.get("executionModes", []))
        card = client.get(f"{BASE}/.well-known/agent-card.json").json()
        check("agent card streaming", card.get("capabilities", {}).get("streaming") is True)

        def submit(payload: dict, headers: dict | None = None) -> dict:
            response = client.post(f"{BASE}/agent/v1/tasks",
                                   headers=headers or internal, json=payload)
            return (response.json().get("data") or {})

        def wait_terminal(task_id: str, timeout: float = 15.0) -> dict:
            deadline = time.time() + timeout
            latest: dict = {}
            while time.time() < deadline:
                latest = client.get(f"{BASE}/agent/v1/tasks/{task_id}",
                                    headers=internal).json().get("data") or {}
                if latest.get("status") in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                    return latest
                time.sleep(0.3)
            return latest

        # 1. 异步只读
        async_task = submit({
            "taskType": "ticket.query",
            "input": {"limit": 2},
            "executionMode": "ASYNC",
            "clientTaskId": f"p4-query-{suffix}",
        })
        check("async accepted PENDING", async_task.get("status") == "PENDING",
              async_task.get("status", ""))

        # 2. 立即订阅 SSE，覆盖“回放 PENDING + 增量 RUNNING/SUCCEEDED + done”
        try:
            stream_lines: list[str] = []
            with client.stream("GET",
                               f"{BASE}/agent/v1/tasks/{async_task['taskId']}/events/stream",
                               headers=internal) as response:
                for line in response.iter_lines():
                    if line:
                        stream_lines.append(line)
                    if line == "event:done":
                        break
            text = "\n".join(stream_lines)
            check("sse task-event", "event:task-event" in text)
            check("sse done", "event:done" in text and "SUCCEEDED" in text)
            check("sse incremental", "RUNNING" in text and "SUCCEEDED" in text)
        except Exception as exc:  # noqa: BLE001
            check("sse stream", False, str(exc))

        done = wait_terminal(async_task["taskId"])
        check("async query succeeded", done.get("status") == "SUCCEEDED",
              (done.get("errorMessage") or ""))
        check("async executionMode", done.get("executionMode") == "ASYNC")
        events = client.get(f"{BASE}/agent/v1/tasks/{async_task['taskId']}/events",
                            headers=internal).json().get("data") or []
        statuses = [item.get("status") for item in events]
        check("async events", statuses == ["PENDING", "RUNNING", "SUCCEEDED"], str(statuses))

        # 3. 委托令牌：scope 限制 + Actor 绑定
        issued = client.post(f"{BASE}/agent/v1/delegation/token", headers=internal, json={
            "actorId": 10003,
            "roles": ["REPORTER"],
            "scopes": ["ticket.query"],
            "ttlSeconds": 120,
        }).json().get("data") or {}
        check("delegation issued", bool(issued.get("token")), issued.get("delegationId", ""))
        delegated_headers = {
            "X-Internal-Token": internal["X-Internal-Token"],
            "X-Delegation-Token": issued.get("token", ""),
            "X-Actor-Id": "10001",
            "X-Actor-Roles": "ADMIN",
            "Content-Type": "application/json",
        }
        delegated = submit({
            "taskType": "ticket.query",
            "input": {"limit": 1},
            "clientTaskId": f"p4-delegated-{suffix}",
        }, delegated_headers)
        check("delegation scope allowed", delegated.get("status") == "SUCCEEDED")
        denied = client.post(f"{BASE}/agent/v1/tasks", headers=delegated_headers, json={
            "taskType": "ticket.export",
            "input": {},
        }).json()
        check("delegation scope denied", denied.get("code") == 10003, str(denied.get("message")))
        spoof = client.get(f"{BASE}/agent/v1/tasks/{delegated['taskId']}",
                           headers=internal).json()
        check("delegation actor bound", spoof.get("code") == 10003, str(spoof.get("message")))

        # 4. 异步写：确认后入队并完成
        created = submit({
            "taskType": "ticket.create",
            "input": {
                "description": f"Agent P4 异步写 {suffix}",
                "ticketTypeCode": "ELEVATOR",
                "locationType": "INDOOR",
                "locationText": "A栋2号梯",
                "buildingId": 101,
                "roomId": 1001,
            },
            "executionMode": "ASYNC",
            "clientTaskId": f"p4-create-{suffix}",
        })
        check("async write waits confirm", created.get("status") == "WAITING_CONFIRM")
        confirmed = client.post(
            f"{BASE}/agent/v1/tasks/{created['taskId']}/confirm",
            headers=internal,
            json={"confirmationToken": created.get("confirmationToken")},
        ).json().get("data") or {}
        check("async confirm PENDING", confirmed.get("status") == "PENDING",
              confirmed.get("status", ""))
        completed = wait_terminal(created["taskId"])
        ticket = ((completed.get("result") or {}).get("ticket") or {})
        check("async write succeeded", completed.get("status") == "SUCCEEDED",
              ticket.get("ticketNo", ""))
        check("confirmation token cleared", completed.get("confirmationToken") is None,
              str(completed.get("confirmationToken")))

        # 5. 委托 scope 同样约束附件上传
        if ticket.get("id"):
            upload_headers = {k: v for k, v in delegated_headers.items() if k != "Content-Type"}
            upload = client.post(
                f"{BASE}/agent/v1/tickets/{ticket['id']}/attachments",
                headers=upload_headers,
                params={"type": "GENERAL"},
                files={"file": ("scope.png", PNG, "image/png")},
            ).json()
            check("delegation attachment scope", upload.get("code") == 10003,
                  str(upload.get("message")))

    failed = [item for item in RESULTS if not item[1]]
    print(f"\npassed={len(RESULTS) - len(failed)} failed={len(failed)} total={len(RESULTS)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
