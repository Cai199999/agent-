#!/usr/bin/env python3
"""子 Agent Stage 5 运行时契约测试。

覆盖：
- Manifest / Agent Card 契约（字段、能力集合、版本一致性）；
- 鉴权契约（内部令牌、委托令牌、Actor 覆盖、越权 403）；
- Task API 请求/响应契约（字段、状态、traceId、幂等、未知能力）；
- 确认令牌契约（错误令牌、非所有者、单次使用、取消后失效）；
- 事件与 SSE 契约（事件字段、状态顺序、done 终止）；
- 双跑回归（原有 /api/v1 登录与工单查询仍然可用）；
- 可选独立 Agent 实例契约（设置 AGENT_STANDALONE_BASE 时）。

运行：
    apps/python-ai/.venv/bin/python scripts/e2e/agent_contract_test.py
    AGENT_STANDALONE_BASE=http://127.0.0.1:8402 \\
        apps/python-ai/.venv/bin/python scripts/e2e/agent_contract_test.py
"""

from __future__ import annotations

import json
import os
import sys
import time
import uuid
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]
BASE = os.environ.get("AGENT_BASE", "http://127.0.0.1:8200").rstrip("/")
STANDALONE_BASE = os.environ.get("AGENT_STANDALONE_BASE", "").rstrip("/")
RESULTS: list[tuple[str, bool, str]] = []

MANIFEST_FIELDS = {
    "agentId", "name", "version", "protocol", "taskEndpoint",
    "eventStreamEndpoint", "executionModes", "auth", "capabilities",
    "contextSchema", "limits", "observability", "downstream", "enforcement",
    "features", "endpoints", "envelope", "healthEndpoint", "readyEndpoint",
    "delegationEndpoint", "asyncDurability",
}
CAPABILITY_FIELDS = {
    "capabilityId", "name", "description", "readOnly", "requiresConfirmation",
    "requiresConfirm", "executionModes", "scopes", "permission", "mode",
    "inputSchema", "outputSchema",
}
TASK_VIEW_FIELDS = {
    "taskId", "agentId", "capability", "capabilityId", "status", "result",
    "errorCode", "errorMessage", "traceId", "latencyMs", "inputTokens",
    "outputTokens", "costMicros", "usage", "executionMode", "delegationId",
    "confirmationToken", "confirmationExpiresAt", "createdAt", "parentTaskId",
    "correlationId", "deadlineAt", "contextRefs", "budget", "clientTaskId",
    "progress", "currentStep", "cancelReason",
}
EVENT_FIELDS = {"id", "seq", "taskId", "type", "status", "message", "createdAt"}
EXPECTED_CAPABILITIES = {
    "ticket.query", "ticket.detail", "ticket.similar", "knowledge.search",
    "ticket.create", "ticket.export", "ticket.assign", "ticket.accept",
    "ticket.resolve", "ticket.verify", "ticket.close", "ticket.cancel",
    "ticket.hold", "ticket.resume", "ticket.finish", "ticket.comment.add",
    "ticket.material.add", "ticket.visit.add",
}
WRITE_CAPABILITIES = EXPECTED_CAPABILITIES - {
    "ticket.query", "ticket.detail", "ticket.similar", "knowledge.search",
}


def env() -> dict[str, str]:
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


def check(name: str, ok: bool, detail: str = "") -> None:
    RESULTS.append((name, ok, detail))
    print(f"{'PASS' if ok else 'FAIL'} {name}{('  ' + detail) if detail else ''}")


def call(client: httpx.Client, method: str, path: str, headers: dict[str, str],
         *, base: str | None = None, **kwargs) -> tuple[int, dict]:
    response = client.request(method, f"{base or BASE}{path}", headers=headers, **kwargs)
    try:
        body = response.json()
    except Exception:  # noqa: BLE001
        body = {"raw": response.text}
    return response.status_code, body


def data_of(body: dict) -> dict:
    return body.get("data") or {} if isinstance(body, dict) else {}


def main() -> int:
    values = env()
    internal_token = values.get("INTERNAL_TOKEN_P2J_CURRENT", "")
    admin = {
        "X-Internal-Token": internal_token,
        "X-Actor-Id": "10001",
        "X-Actor-Roles": "ADMIN",
        "Content-Type": "application/json",
    }
    other = {
        "X-Internal-Token": internal_token,
        "X-Actor-Id": "10002",
        "X-Actor-Roles": "ENGINEER",
        "Content-Type": "application/json",
    }
    suffix = f"{int(time.time())}-{uuid.uuid4().hex[:6]}"

    with httpx.Client(timeout=30.0, follow_redirects=False) as client:
        # ---------- 1. Manifest 契约 ----------
        status, body = call(client, "GET", "/agent/v1/manifest", admin)
        manifest = data_of(body)
        check("manifest http/envelope", status == 200 and body.get("code") == 0,
              f"http={status} code={body.get('code')}")
        check("manifest required fields", MANIFEST_FIELDS <= set(manifest), str(set(manifest)))
        check("manifest version", manifest.get("version") == "0.8.0",
              str(manifest.get("version")))
        check("manifest protocol", manifest.get("protocol") == "agent-task-v1",
              str(manifest.get("protocol")))
        check("manifest features",
              (manifest.get("features") or {}).get("clientTaskLookup") is True
              and (manifest.get("features") or {}).get("lastEventId") is True,
              str(manifest.get("features")))
        check("manifest endpoints",
              (manifest.get("endpoints") or {}).get("confirm")
              == "/agent/v1/tasks/{taskId}/confirm"
              and (manifest.get("endpoints") or {}).get("taskByClientTaskId")
              == "/agent/v1/tasks?clientTaskId={clientTaskId}",
              str(manifest.get("endpoints")))
        check("manifest health/ready/delegation",
              manifest.get("healthEndpoint") == "/agent/v1/health"
              and manifest.get("readyEndpoint") == "/agent/v1/ready"
              and manifest.get("delegationEndpoint") == "/agent/v1/delegation/token",
              str({key: manifest.get(key) for key in
                   ("healthEndpoint", "readyEndpoint", "delegationEndpoint")}))
        envelope = manifest.get("envelope") or {}
        check("manifest envelope",
              envelope.get("successCode") == 0
              and "REJECTED" in (envelope.get("taskStatuses") or []),
              str(envelope))
        check("manifest async durability",
              (manifest.get("asyncDurability") or {}).get("mode") == "db-recovery",
              str(manifest.get("asyncDurability")))
        check("manifest context schema",
              {"parentTaskId", "correlationId", "deadlineAt", "budget", "contextRefs"}
              <= set(manifest.get("contextSchema") or {}))
        check("manifest observability",
              (manifest.get("observability") or {}).get("metricsEndpoint")
              == "/agent/v1/metrics/summary", str(manifest.get("observability")))
        check("manifest downstream reserved",
              (manifest.get("downstream") or {}).get("enabled") is False
              and (manifest.get("downstream") or {}).get("protocol") == "agent-task-v1",
              str(manifest.get("downstream")))
        check("manifest input limit",
              (manifest.get("limits") or {}).get("maxInputBytes", 0) > 0,
              str(manifest.get("limits")))
        enforcement = manifest.get("enforcement") or {}
        check("manifest deadline enforcement declared",
              (enforcement.get("deadlineAt") or {}).get("mode") == "checked-before-execution",
              str(enforcement))
        check("manifest execution modes",
              set(manifest.get("executionModes") or []) == {"SYNC", "ASYNC"})
        check("manifest task endpoint", manifest.get("taskEndpoint") == "/agent/v1/tasks")
        check("manifest stream endpoint",
              manifest.get("eventStreamEndpoint") == "/agent/v1/tasks/{taskId}/events/stream")
        auth = manifest.get("auth") or {}
        check("manifest auth headers",
              auth.get("header") == "X-Internal-Token"
              and auth.get("delegationHeader") == "X-Delegation-Token", str(auth))

        capabilities = manifest.get("capabilities") or []
        names = [item.get("name") for item in capabilities]
        check("capability set frozen", set(names) == EXPECTED_CAPABILITIES,
              f"missing={EXPECTED_CAPABILITIES - set(names)} extra={set(names) - EXPECTED_CAPABILITIES}")
        check("capability names unique", len(names) == len(set(names)))
        schema_ok = all(CAPABILITY_FIELDS <= set(item) for item in capabilities)
        check("capability schema", schema_ok)
        check("capability ids", all(
            str(item.get("capabilityId", "")).startswith("property.")
            for item in capabilities))
        check("capability json schemas", all(
            isinstance(item.get("inputSchema"), dict)
            and item.get("inputSchema", {}).get("type") == "object"
            and isinstance(item.get("outputSchema"), dict)
            for item in capabilities))
        check("capability readOnly/executionModes/scopes", all(
            item.get("readOnly") is (item.get("mode") == "READ_ONLY")
            and item.get("executionModes") == ["SYNC", "ASYNC"]
            and item.get("scopes") == [item.get("name")]
            for item in capabilities))
        write_confirm_ok = all(
            item.get("requiresConfirm") is True
            for item in capabilities if item.get("name") in WRITE_CAPABILITIES)
        read_no_confirm_ok = all(
            item.get("requiresConfirm") is False
            for item in capabilities if item.get("name") not in WRITE_CAPABILITIES)
        check("write capabilities require confirm", write_confirm_ok)
        check("read capabilities require no confirm", read_no_confirm_ok)
        check("capability mode enum",
              all(item.get("mode") in {"READ_ONLY", "WRITE"} for item in capabilities))

        # ---------- 2. Agent Card 契约 ----------
        status, card = call(client, "GET", "/.well-known/agent-card.json", {})
        check("agent card http", status == 200, f"http={status}")
        check("agent card version matches manifest",
              card.get("version") == manifest.get("version"), str(card.get("version")))
        check("agent card protocol matches manifest",
              card.get("protocolVersion") == manifest.get("protocol"))
        check("agent card skills match manifest",
              [item.get("name") for item in card.get("skills") or []] == names)
        check("agent card streaming", (card.get("capabilities") or {}).get("streaming") is True)
        schemes = card.get("securitySchemes") or {}
        check("agent card security schemes", {"internalToken", "delegationToken"} <= set(schemes))
        check("agent card hides secrets", "secret" not in json.dumps(schemes).lower())

        # ---------- 2.1 健康与就绪 ----------
        status, body = call(client, "GET", "/agent/v1/health", {})
        health = data_of(body)
        check("agent health no token", status == 200 and body.get("code") == 0
              and health.get("status") == "UP"
              and health.get("version") == manifest.get("version"),
              f"http={status} data={health}")
        status, body = call(client, "GET", "/agent/v1/ready", {})
        ready = data_of(body)
        check("agent ready no token", status == 200 and body.get("code") == 0
              and ready.get("ready") is True
              and (ready.get("checks") or {}).get("database") == "UP",
              f"http={status} data={ready}")

        # ---------- 3. 鉴权契约 ----------
        no_token = {"Content-Type": "application/json"}
        status, body = call(client, "GET", "/agent/v1/manifest", no_token)
        check("auth missing token", status == 401 and body.get("code") == 10001,
              f"http={status} code={body.get('code')}")
        bad_token = dict(no_token, **{"X-Internal-Token": "wrong-token"})
        status, body = call(client, "GET", "/agent/v1/manifest", bad_token)
        check("auth invalid token", status == 401 and body.get("code") == 10001,
              f"http={status} code={body.get('code')}")
        bad_delegation = dict(admin, **{"X-Delegation-Token": "a.b.c"})
        status, body = call(client, "POST", "/agent/v1/tasks", bad_delegation,
                            json={"taskType": "ticket.query", "input": {}})
        check("auth invalid delegation", status == 401 and body.get("code") == 10001,
              f"http={status} code={body.get('code')}")

        # ---------- 4. Task API 契约 ----------
        trace_id = uuid.uuid4().hex
        task_headers = dict(admin, **{"X-Trace-Id": trace_id})
        status, body = call(client, "POST", "/agent/v1/tasks", task_headers, json={
            "taskType": "ticket.query",
            "input": {"limit": 1},
            "clientTaskId": f"contract-read-{suffix}",
        })
        task = data_of(body)
        check("task submit http/envelope", status == 200 and body.get("code") == 0,
              f"http={status} code={body.get('code')}")
        check("task view fields", TASK_VIEW_FIELDS <= set(task), str(set(task)))
        check("task sync succeeded", task.get("status") == "SUCCEEDED",
              task.get("errorMessage") or task.get("status", ""))
        check("task execution mode", task.get("executionMode") == "SYNC")
        check("task agent id", task.get("agentId") == "property-maintenance")
        check("task capability", task.get("capability") == "ticket.query")
        check("task capabilityId", task.get("capabilityId") == "property.ticket.query")
        check("task clientTaskId", task.get("clientTaskId")
              == f"contract-read-{suffix}", str(task.get("clientTaskId")))
        check("task trace propagation", task.get("traceId") == trace_id, str(task.get("traceId")))
        check("task latency numeric", isinstance(task.get("latencyMs"), int))
        check("task result is list", isinstance(task.get("result"), list))
        check("task initial tokens/cost reserved",
              task.get("inputTokens") is None and task.get("outputTokens") is None
              and task.get("costMicros") is None)
        usage = task.get("usage") or {}
        check("task usage envelope", usage.get("source") == "not-measured", str(usage))
        check("task progress/currentStep",
              task.get("progress") == 100 and task.get("currentStep") == "DONE",
              f"{task.get('progress')}/{task.get('currentStep')}")

        # clientTaskId 恢复查询（主 Agent 崩溃恢复）
        status, body = call(client, "GET",
                            f"/agent/v1/tasks?clientTaskId=contract-read-{suffix}", admin)
        check("task lookup by clientTaskId",
              status == 200 and data_of(body).get("taskId") == task.get("taskId"),
              f"http={status}")
        status, body = call(client, "GET",
                            f"/agent/v1/tasks?clientTaskId=missing-{suffix}", admin)
        check("task lookup missing 404", status == 404 and body.get("code") == 20001,
              f"http={status} code={body.get('code')}")

        # 跨 Agent 上下文参数预留
        _, body = call(client, "POST", "/agent/v1/tasks", admin, json={
            "taskType": "ticket.query",
            "input": {"limit": 1},
            "contextRefs": {"ticketId": "1001", "kind": "TICKET"},
            "parentTaskId": "parent-task-contract",
            "correlationId": "correlation-contract",
            "deadlineAt": "2030-01-01T00:00:00+08:00",
            "budget": {"maxTokens": 100, "maxLatencyMs": 5000},
            "clientTaskId": f"contract-context-{suffix}",
        })
        contextual = data_of(body)
        check("context params echoed",
              contextual.get("parentTaskId") == "parent-task-contract"
              and contextual.get("correlationId") == "correlation-contract"
              and contextual.get("deadlineAt", "").startswith("2029-12-31T16:00:00")
              and (contextual.get("contextRefs") or {}).get("ticketId") == "1001"
              and (contextual.get("budget") or {}).get("maxTokens") == 100,
              str({key: contextual.get(key) for key in
                   ("parentTaskId", "correlationId", "deadlineAt", "contextRefs", "budget")}))
        _, body = call(client, "GET",
                       f"/agent/v1/tasks/{contextual['taskId']}", admin)
        check("context params persisted",
              data_of(body).get("parentTaskId") == "parent-task-contract"
              and data_of(body).get("correlationId") == "correlation-contract")
        status, body = call(client, "POST", "/agent/v1/tasks", admin, json={
            "taskType": "ticket.query", "input": {}, "deadlineAt": "not-a-time"})
        check("invalid deadlineAt 400", status == 400 and body.get("code") == 60001,
              f"http={status} code={body.get('code')}")

        # 幂等
        _, replay = call(client, "POST", "/agent/v1/tasks", admin, json={
            "taskType": "ticket.query",
            "input": {"limit": 1},
            "clientTaskId": f"contract-read-{suffix}",
        })
        check("task clientTaskId idempotent",
              data_of(replay).get("taskId") == task.get("taskId"))
        _, other_actor_same_key = call(client, "POST", "/agent/v1/tasks", other, json={
            "taskType": "ticket.query",
            "input": {"limit": 1},
            "clientTaskId": f"contract-read-{suffix}",
        })
        check("task idempotency scoped by actor",
              data_of(other_actor_same_key).get("taskId") not in {None, task.get("taskId")})

        # 读取与越权
        status, body = call(client, "GET", f"/agent/v1/tasks/{task['taskId']}", admin)
        check("task get owner", status == 200 and data_of(body).get("taskId") == task["taskId"])
        status, body = call(client, "GET", f"/agent/v1/tasks/{task['taskId']}", other)
        check("task get non-owner 403", status == 403 and body.get("code") == 10003,
              f"http={status} code={body.get('code')}")
        status, body = call(client, "GET", f"/agent/v1/tasks/{uuid.uuid4()}", admin)
        check("task not found", status == 404 and body.get("code") == 20001,
              f"http={status} code={body.get('code')}")

        # 未知能力 / 非法模式 / 空 taskType
        _, body = call(client, "POST", "/agent/v1/tasks", admin, json={
            "taskType": "ticket.unknown",
            "input": {},
            "clientTaskId": f"contract-unknown-{suffix}",
        })
        unknown = data_of(body)
        check("unknown capability fails task",
              unknown.get("status") == "FAILED" and unknown.get("errorCode") == "60001",
              f"{unknown.get('status')}/{unknown.get('errorCode')}")
        status, body = call(client, "POST", "/agent/v1/tasks", admin, json={
            "taskType": "ticket.query", "executionMode": "BATCH"})
        check("invalid execution mode 400", status == 400 and body.get("code") == 60001,
              f"http={status} code={body.get('code')}")
        status, body = call(client, "POST", "/agent/v1/tasks", admin,
                            json={"taskType": "", "input": {}})
        check("blank task type 400", status == 400 and body.get("code") == 60001,
              f"http={status} code={body.get('code')}")

        # ---------- 5. 确认令牌契约 ----------
        create_payload = {
            "taskType": "ticket.create",
            "input": {
                "description": f"Stage5 契约测试 {suffix}",
                "ticketTypeCode": "ELEVATOR",
                "locationType": "INDOOR",
                "locationText": "A栋2号梯",
                "buildingId": 101,
                "roomId": 1001,
            },
            "clientTaskId": f"contract-create-{suffix}",
        }
        _, body = call(client, "POST", "/agent/v1/tasks", admin, json=create_payload)
        write_task = data_of(body)
        token = write_task.get("confirmationToken")
        check("write waits confirm", write_task.get("status") == "WAITING_CONFIRM",
              str(write_task.get("status")))
        check("write exposes one-time token",
              isinstance(token, str) and len(token) >= 16, str(type(token)))
        check("write exposes expiry", bool(write_task.get("confirmationExpiresAt")))
        check("write not executed before confirm",
              (write_task.get("result") or {}).get("ticket") is None,
              str(write_task.get("result")))

        # 幂等不重复建单：重复提交返回同一任务
        _, replay = call(client, "POST", "/agent/v1/tasks", admin, json=create_payload)
        check("write idempotent before confirm",
              data_of(replay).get("taskId") == write_task.get("taskId")
              and data_of(replay).get("status") == "WAITING_CONFIRM")

        status, body = call(client, "POST",
                            f"/agent/v1/tasks/{write_task['taskId']}/confirm", admin,
                            json={"confirmationToken": "wrong-token"})
        check("confirm wrong token 403", status == 403 and body.get("code") == 10003,
              f"http={status} code={body.get('code')}")
        status, body = call(client, "POST",
                            f"/agent/v1/tasks/{write_task['taskId']}/confirm", other,
                            json={"confirmationToken": token})
        check("confirm non-owner 403", status == 403 and body.get("code") == 10003,
              f"http={status} code={body.get('code')}")

        status, body = call(client, "POST",
                            f"/agent/v1/tasks/{write_task['taskId']}/confirm", admin,
                            json={"confirmationToken": token})
        confirmed = data_of(body)
        ticket = (confirmed.get("result") or {}).get("ticket") or {}
        check("confirm executes write", status == 200 and confirmed.get("status") == "SUCCEEDED",
              confirmed.get("errorMessage") or confirmed.get("status", ""))
        check("confirm clears token", confirmed.get("confirmationToken") is None)
        check("confirmed ticket persisted",
              bool(ticket.get("id")) and bool(ticket.get("ticketNo")), str(ticket.get("ticketNo")))

        status, body = call(client, "POST",
                            f"/agent/v1/tasks/{write_task['taskId']}/confirm", admin,
                            json={"confirmationToken": token})
        check("confirm token single use", status == 409 and body.get("code") == 20002,
              f"http={status} code={body.get('code')}")

        # 拒绝确认：进入 REJECTED，不执行业务
        _, body = call(client, "POST", "/agent/v1/tasks", admin, json={
            "taskType": "ticket.create",
            "input": dict(create_payload["input"], description=f"Stage5 拒绝 {suffix}"),
            "clientTaskId": f"contract-reject-{suffix}",
        })
        reject_task = data_of(body)
        reject_token = reject_task.get("confirmationToken")
        status, body = call(client, "POST",
                            f"/agent/v1/tasks/{reject_task['taskId']}/confirm", admin,
                            json={"confirmationToken": reject_token, "decision": "MAYBE"})
        check("confirm invalid decision 400", status == 400 and body.get("code") == 60001,
              f"http={status} code={body.get('code')}")
        status, body = call(client, "POST",
                            f"/agent/v1/tasks/{reject_task['taskId']}/confirm", admin,
                            json={"confirmationToken": reject_token, "decision": "REJECT"})
        rejected = data_of(body)
        check("confirm reject", status == 200 and rejected.get("status") == "REJECTED",
              f"http={status} status={rejected.get('status')}")
        check("reject clears token", rejected.get("confirmationToken") is None)
        check("reject does not create ticket",
              (rejected.get("result") or {}).get("ticket") is None,
              str(rejected.get("result")))

        # 取消后令牌失效（带取消原因）
        _, body = call(client, "POST", "/agent/v1/tasks", admin, json={
            "taskType": "ticket.create",
            "input": dict(create_payload["input"], description=f"Stage5 取消 {suffix}"),
            "clientTaskId": f"contract-cancel-{suffix}",
        })
        cancel_task = data_of(body)
        _, body = call(client, "POST", f"/agent/v1/tasks/{cancel_task['taskId']}/cancel", admin,
                       json={"reason": "契约测试取消"})
        cancelled = data_of(body)
        check("cancel waiting confirm", cancelled.get("status") == "CANCELLED",
              str(cancelled.get("status")))
        check("cancel clears token", cancelled.get("confirmationToken") is None)
        check("cancel reason echoed", cancelled.get("cancelReason") == "契约测试取消",
              str(cancelled.get("cancelReason")))
        status, body = call(client, "POST",
                            f"/agent/v1/tasks/{cancel_task['taskId']}/confirm", admin,
                            json={"confirmationToken": cancel_task.get("confirmationToken")})
        check("confirm after cancel rejected", status == 409 and body.get("code") == 20002,
              f"http={status} code={body.get('code')}")

        # ---------- 6. 委托令牌契约 ----------
        status, body = call(client, "POST", "/agent/v1/delegation/token", admin, json={
            "actorId": 10003,
            "roles": ["REPORTER"],
            "scopes": ["ticket.query"],
            "ttlSeconds": 120,
        })
        issued = data_of(body)
        check("delegation issued", status == 200 and bool(issued.get("token"))
              and bool(issued.get("delegationId")) and bool(issued.get("expiresAt")))
        delegated = {
            "X-Internal-Token": internal_token,
            "X-Delegation-Token": issued.get("token", ""),
            "X-Actor-Id": "10001",
            "X-Actor-Roles": "ADMIN",
            "Content-Type": "application/json",
        }
        _, body = call(client, "POST", "/agent/v1/tasks", delegated, json={
            "taskType": "ticket.query",
            "input": {"limit": 1},
            "clientTaskId": f"contract-delegated-{suffix}",
        })
        delegated_task = data_of(body)
        check("delegation allows scoped capability",
              delegated_task.get("status") == "SUCCEEDED",
              delegated_task.get("errorMessage") or delegated_task.get("status", ""))
        status, body = call(client, "GET",
                            f"/agent/v1/tasks/{delegated_task['taskId']}", admin)
        check("delegation actor binding",
              status == 403 and body.get("code") == 10003,
              f"http={status} code={body.get('code')}")
        status, body = call(client, "POST", "/agent/v1/tasks", delegated,
                            json={"taskType": "ticket.create", "input": {}})
        check("delegation scope denied", status == 403 and body.get("code") == 10003,
              f"http={status} code={body.get('code')}")
        status, body = call(client, "POST", "/agent/v1/delegation/token", delegated, json={
            "actorId": 10004, "roles": ["ADMIN"], "scopes": ["*"], "ttlSeconds": 120})
        check("delegation cannot re-delegate", status == 403 and body.get("code") == 10003,
              f"http={status} code={body.get('code')}")

        # ---------- 7. 事件与 SSE 契约 ----------
        _, body = call(client, "POST", "/agent/v1/tasks", admin, json={
            "taskType": "ticket.query",
            "input": {"limit": 1},
            "executionMode": "ASYNC",
            "clientTaskId": f"contract-async-{suffix}",
        })
        async_task = data_of(body)
        check("async accepted pending", async_task.get("status") == "PENDING",
              str(async_task.get("status")))

        deadline = time.time() + 15
        final = async_task
        while time.time() < deadline:
            _, body = call(client, "GET", f"/agent/v1/tasks/{async_task['taskId']}", admin)
            final = data_of(body)
            if final.get("status") in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                break
            time.sleep(0.3)
        check("async reaches terminal", final.get("status") == "SUCCEEDED",
              final.get("errorMessage") or final.get("status", ""))

        _, body = call(client, "GET",
                       f"/agent/v1/tasks/{async_task['taskId']}/events", admin)
        events = body.get("data") or []
        statuses = [item.get("status") for item in events]
        check("event history order", statuses == ["PENDING", "RUNNING", "SUCCEEDED"], str(statuses))
        check("event fields", all(EVENT_FIELDS <= set(item) for item in events))
        check("event task binding",
              all(item.get("taskId") == async_task["taskId"] for item in events))
        check("event ids ascending",
              [int(item["id"]) for item in events] == sorted(int(item["id"]) for item in events))
        check("event seq contiguous", [item.get("seq") for item in events] == [1, 2, 3],
              str([item.get("seq") for item in events]))
        check("event types",
              [item.get("type") for item in events]
              == ["TASK_QUEUED", "TASK_STARTED", "TASK_SUCCEEDED"],
              str([item.get("type") for item in events]))
        _, body = call(client, "GET",
                       f"/agent/v1/tasks/{async_task['taskId']}/events?afterSeq=1", admin)
        after_events = body.get("data") or []
        check("event afterSeq cursor",
              [item.get("seq") for item in after_events] == [2, 3],
              str([item.get("seq") for item in after_events]))

        # SSE：完成的任务先回放历史，随后 done 关闭
        stream_names: list[str] = []
        stream_ids: list[str] = []
        done_payload: dict = {}
        event_name = None
        data_lines: list[str] = []
        try:
            with client.stream(
                    "GET",
                    f"{BASE}/agent/v1/tasks/{async_task['taskId']}/events/stream",
                    headers=admin) as response:
                for line in response.iter_lines():
                    if line.startswith("event:"):
                        event_name = line[6:].strip()
                    elif line.startswith("id:"):
                        stream_ids.append(line[3:].strip())
                    elif line.startswith("data:"):
                        data_lines.append(line[5:].strip())
                    elif line.strip() == "":
                        if event_name:
                            stream_names.append(event_name)
                            if event_name == "done":
                                try:
                                    done_payload = json.loads("\n".join(data_lines))
                                except json.JSONDecodeError:
                                    done_payload = {}
                                break
                        event_name = None
                        data_lines = []
            check("sse replays task-event", "task-event" in stream_names, str(stream_names))
            check("sse ids use seq", stream_ids[:3] == ["1", "2", "3"], str(stream_ids))
            check("sse terminal done", stream_names.count("done") == 1, str(stream_names))
            check("sse done payload",
                  done_payload.get("taskId") == async_task["taskId"]
                  and done_payload.get("status") == "SUCCEEDED", str(done_payload))
        except Exception as exc:  # noqa: BLE001
            check("sse stream", False, str(exc))

        # 断线重连：Last-Event-ID 已越过终态事件时立即 done，不重复回放
        reconnect_names: list[str] = []
        try:
            with client.stream(
                    "GET",
                    f"{BASE}/agent/v1/tasks/{async_task['taskId']}/events/stream",
                    headers=dict(admin, **{"Last-Event-ID": "3"})) as response:
                for line in response.iter_lines():
                    if line.startswith("event:"):
                        reconnect_names.append(line[6:].strip())
                        if reconnect_names[-1] == "done":
                            break
            check("sse last-event-id reconnect",
                  reconnect_names.count("done") == 1 and "task-event" not in reconnect_names,
                  str(reconnect_names))
        except Exception as exc:  # noqa: BLE001
            check("sse last-event-id reconnect", False, str(exc))

        status, body = call(client, "GET",
                            f"/agent/v1/tasks/{async_task['taskId']}/events/stream", other)
        check("sse non-owner 403", status == 403 and body.get("code") == 10003,
              f"http={status} code={body.get('code')}")

        # ---------- 8. 可观测性聚合契约 ----------
        status, body = call(client, "GET", "/agent/v1/metrics/summary", admin)
        summary = data_of(body)
        agent_metrics = summary.get("agentTasks") or {}
        chat_metrics = summary.get("chat") or {}
        tool_metrics = summary.get("toolCalls") or {}
        check("metrics summary envelope", status == 200 and body.get("code") == 0,
              f"http={status} code={body.get('code')}")
        check("metrics summary sections",
              {"from", "to", "agentTasks", "chat", "toolCalls"} <= set(summary))
        check("metrics agent task fields",
              {"total", "active", "terminal", "successRate", "byStatus",
               "byCapability", "p95LatencyMs", "inputTokens", "outputTokens",
               "costMicros"} <= set(agent_metrics))
        check("metrics agent task counted", agent_metrics.get("total", 0) > 0,
              str(agent_metrics.get("total")))
        check("metrics chat fields",
              {"assistantMessages", "failed", "inputTokens", "outputTokens",
               "avgLatencyMs", "p95LatencyMs"} <= set(chat_metrics))
        check("metrics tool call fields",
              {"total", "byTool", "byStatus"} <= set(tool_metrics))
        status, body = call(client, "GET", "/agent/v1/metrics/summary", other)
        check("metrics requires admin", status == 403 and body.get("code") == 10003,
              f"http={status} code={body.get('code')}")
        status, body = call(client, "GET",
                            "/agent/v1/metrics/summary?from=2026-11-20T00:00:00Z"
                            "&to=2026-11-19T00:00:00Z", admin)
        check("metrics invalid range 400", status == 400 and body.get("code") == 60001,
              f"http={status} code={body.get('code')}")

        # ---------- 9. 双跑回归：原有用户端 API 不受影响 ----------
        password = values.get("DEV_ADMIN_PASSWORD", "")
        login_ok = False
        user_token = None
        if password:
            status, body = call(client, "POST", "/api/v1/auth/login",
                                {"Content-Type": "application/json"},
                                json={"username": values.get("DEV_ADMIN_USERNAME", "admin"),
                                      "password": password})
            user_token = data_of(body).get("accessToken")
            login_ok = status == 200 and body.get("code") == 0 and bool(user_token)
            check("dual-run user login", login_ok, f"http={status}")
        else:
            check("dual-run user login", False, "缺少 DEV_ADMIN_PASSWORD")
        if user_token:
            status, body = call(client, "GET", "/api/v1/tickets?page=1&size=1",
                                {"Authorization": f"Bearer {user_token}"})
            check("dual-run ticket list", status == 200 and body.get("code") == 0,
                  f"http={status} code={body.get('code')}")
            status, body = call(client, "GET", f"/api/v1/tickets/{ticket.get('id')}",
                                {"Authorization": f"Bearer {user_token}"})
            detail = data_of(body).get("ticket") or data_of(body)
            check("dual-run confirmed ticket visible",
                  status == 200 and detail.get("ticketNo") == ticket.get("ticketNo"),
                  f"http={status} ticketNo={detail.get('ticketNo')}")
            status, body = call(client, "GET", "/api/v1/health/ai",
                                {"Authorization": f"Bearer {user_token}"})
            check("dual-run ai health", status == 200 and body.get("code") == 0,
                  f"http={status}")

        # ---------- 10. 可选：独立 Agent 实例 ----------
        if STANDALONE_BASE:
            status, body = call(client, "GET", "/agent/v1/manifest", admin,
                                base=STANDALONE_BASE)
            check("standalone manifest", status == 200 and data_of(body).get("version")
                  == manifest.get("version"), f"http={status}")
            status, card = call(client, "GET", "/.well-known/agent-card.json", {},
                                base=STANDALONE_BASE)
            check("standalone agent card", status == 200 and card.get("name"))
            status, _ = call(client, "POST", "/api/v1/auth/login",
                             {"Content-Type": "application/json"},
                             base=STANDALONE_BASE,
                             json={"username": "admin", "password": password})
            check("standalone hides user API", status == 404, f"http={status}")
            status, _ = call(client, "GET", "/actuator/health", {}, base=STANDALONE_BASE)
            check("standalone health", status == 200, f"http={status}")

    failed = [item for item in RESULTS if not item[1]]
    print(f"\npassed={len(RESULTS) - len(failed)} failed={len(failed)} total={len(RESULTS)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
