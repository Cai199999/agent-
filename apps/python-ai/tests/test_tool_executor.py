"""工具执行器测试：超时、错误分级与参数校验。

不连接 Java / MySQL：用假网关验证工具层行为。
"""

from __future__ import annotations

import asyncio
import unittest

import httpx

from adapters.gateways.java_internal_client import JavaInternalClient, JavaInternalError
from app.tool_executor import ToolExecutorImpl
from domain.tool import ToolCall, ToolRiskLevel, ToolSpec


class FakeRegistry:
    def __init__(self, spec: ToolSpec) -> None:
        self.spec = spec

    def get(self, name: str):
        return self.spec if name == self.spec.name else None


class FakeGateway:
    def __init__(self, delay: float = 0.0, error: Exception | None = None) -> None:
        self.delay = delay
        self.error = error
        self.calls = 0
        self.last_kwargs: dict = {}

    async def list_tickets(self, actor, status, keyword, limit, **kwargs):
        self.calls += 1
        self.last_kwargs = kwargs
        if self.delay:
            await asyncio.sleep(self.delay)
        if self.error:
            raise self.error
        return {"items": [{
            "id": "1", "ticketNo": "GD1", "title": "t", "statusCode": "PENDING",
            "priorityCode": "URGENT", "assigneeId": 10002,
            "slaResolveDueAt": "2026-11-20T00:00:00Z", "createdAt": "2026-11-19T00:00:00Z",
        }]}

    async def lookup_users(self, actor, keyword, limit, trace_id=None):
        self.calls += 1
        return {"items": [{"id": "10002", "username": "engineer1",
                           "displayName": "张工（运维）"}]}

    async def get_ticket(self, actor, ticket_id, trace_id=None):
        return {
            "ticket": {"id": "1", "ticketNo": "GD1", "title": "t",
                       "statusCode": "PENDING", "assigneeId": 10002},
            "assigneeName": "张工",
            "reporterName": "李业主",
            "sla": {"resolveDueAt": "2026-11-20T00:00:00Z"},
            "description": "描述",
            "events": [],
        }


def spec(timeout_seconds: float = 0.05) -> ToolSpec:
    return ToolSpec(
        name="ticket_query",
        description="query",
        input_schema={"type": "object"},
        output_schema={"type": "object"},
        permission="ticket:read",
        risk_level=ToolRiskLevel.LOW,
        idempotent=True,
        timeout_seconds=timeout_seconds,
    )


class ToolExecutorTest(unittest.IsolatedAsyncioTestCase):

    async def test_slow_tool_times_out(self):
        gateway = FakeGateway(delay=0.2)
        executor = ToolExecutorImpl(FakeRegistry(spec(timeout_seconds=0.05)), gateway)

        result = await executor.execute(ToolCall("c1", "ticket_query", {}),
                                        {"actor_id": 1, "roles": ["ADMIN"]})

        self.assertFalse(result.success)
        self.assertEqual(result.error_code, "tool_timeout")
        self.assertEqual(gateway.calls, 1)

    async def test_forbidden_error_is_not_retryable_class(self):
        gateway = FakeGateway(error=JavaInternalError("无权限", "tool_forbidden"))
        executor = ToolExecutorImpl(FakeRegistry(spec()), gateway)

        result = await executor.execute(ToolCall("c1", "ticket_query", {}),
                                        {"actor_id": 1, "roles": ["ADMIN"]})

        self.assertFalse(result.success)
        self.assertEqual(result.error_code, "tool_forbidden")

    async def test_invalid_status_rejected_before_gateway(self):
        gateway = FakeGateway()
        executor = ToolExecutorImpl(FakeRegistry(spec()), gateway)

        result = await executor.execute(
            ToolCall("c1", "ticket_query", {"status": "NOT_A_STATUS"}),
            {"actor_id": 1, "roles": ["ADMIN"]})

        self.assertFalse(result.success)
        self.assertEqual(result.error_code, "tool_invalid_arguments")
        self.assertEqual(gateway.calls, 0)

    async def test_query_filters_are_forwarded(self):
        gateway = FakeGateway()
        executor = ToolExecutorImpl(FakeRegistry(spec()), gateway)

        result = await executor.execute(ToolCall("c1", "ticket_query", {
            "priority_code": "urgent",
            "assignee_id": "10002",
            "start_time": "2026-11-01T00:00:00+08:00",
            "end_time": "2026-11-20T00:00:00+08:00",
            "open_only": True,
        }), {"actor_id": 1, "roles": ["ADMIN"]})

        self.assertTrue(result.success)
        self.assertEqual(gateway.last_kwargs["priority"], "URGENT")
        self.assertEqual(gateway.last_kwargs["assignee_id"], "10002")
        self.assertTrue(gateway.last_kwargs["open_only"])
        self.assertTrue(gateway.last_kwargs["start_time"].startswith("2026-11-01"))
        self.assertEqual(result.data["tickets"][0]["assignee_id"], "10002")
        self.assertEqual(result.data["tickets"][0]["sla_resolve_due_at"],
                         "2026-11-20T00:00:00Z")

    async def test_write_tool_returns_confirm_draft(self):
        gateway = FakeGateway()
        write_spec = ToolSpec(
            name="ticket_assign", description="assign", input_schema={"type": "object"},
            output_schema={"type": "object"}, permission="ticket:assign",
            risk_level=ToolRiskLevel.HIGH, requires_confirm=True,
            idempotent=False, timeout_seconds=1)
        executor = ToolExecutorImpl(FakeRegistry(write_spec), gateway)

        result = await executor.execute(
            ToolCall("c1", "ticket_assign",
                     {"ticket_id": "GD1", "assignee_id": "10002", "reason": "改派"}),
            {"actor_id": 1, "roles": ["ADMIN"]})

        self.assertTrue(result.success)
        self.assertEqual(result.data["state"], "CONFIRM_REQUIRED")
        self.assertEqual(result.data["action"], "ticket_assign")
        self.assertIn("10002", result.data["summary"])
        self.assertEqual(gateway.calls, 0)

    async def test_write_tool_requires_ticket_id(self):
        gateway = FakeGateway()
        write_spec = ToolSpec(
            name="ticket_hold", description="hold", input_schema={"type": "object"},
            output_schema={"type": "object"}, permission="ticket:handle",
            risk_level=ToolRiskLevel.HIGH, requires_confirm=True,
            idempotent=False, timeout_seconds=1)
        executor = ToolExecutorImpl(FakeRegistry(write_spec), gateway)

        result = await executor.execute(
            ToolCall("c1", "ticket_hold", {"reason": "等配件"}),
            {"actor_id": 1, "roles": ["ADMIN"]})

        self.assertFalse(result.success)
        self.assertEqual(result.error_code, "tool_invalid_arguments")

    async def test_write_tool_assignee_must_be_numeric(self):
        gateway = FakeGateway()
        write_spec = ToolSpec(
            name="ticket_assign", description="assign", input_schema={"type": "object"},
            output_schema={"type": "object"}, permission="ticket:assign",
            risk_level=ToolRiskLevel.HIGH, requires_confirm=True,
            idempotent=False, timeout_seconds=1)
        executor = ToolExecutorImpl(FakeRegistry(write_spec), gateway)

        result = await executor.execute(
            ToolCall("c1", "ticket_assign",
                     {"ticket_id": "GD1", "assignee_id": "张工"}),
            {"actor_id": 1, "roles": ["ADMIN"]})

        self.assertFalse(result.success)
        self.assertEqual(result.error_code, "tool_invalid_arguments")

    async def test_user_lookup_returns_id_and_name(self):
        gateway = FakeGateway()
        lookup_spec = ToolSpec(
            name="user_lookup", description="lookup", input_schema={"type": "object"},
            output_schema={"type": "object"}, permission="ticket:read",
            risk_level=ToolRiskLevel.LOW, idempotent=True, timeout_seconds=1)
        executor = ToolExecutorImpl(FakeRegistry(lookup_spec), gateway)

        result = await executor.execute(
            ToolCall("c1", "user_lookup", {"keyword": "张工"}),
            {"actor_id": 1, "roles": ["ADMIN"]})

        self.assertTrue(result.success)
        self.assertEqual(result.data["users"][0]["id"], "10002")
        self.assertEqual(result.data["users"][0]["display_name"], "张工（运维）")

    async def test_detail_includes_names_and_sla(self):
        gateway = FakeGateway()
        executor = ToolExecutorImpl(FakeRegistry(spec()), gateway)
        detail_spec = ToolSpec(
            name="ticket_detail", description="detail", input_schema={"type": "object"},
            output_schema={"type": "object"}, permission="ticket:read",
            risk_level=ToolRiskLevel.LOW, idempotent=True, timeout_seconds=1)
        executor = ToolExecutorImpl(FakeRegistry(detail_spec), gateway)

        result = await executor.execute(
            ToolCall("c1", "ticket_detail", {"ticket_id": "1"}),
            {"actor_id": 1, "roles": ["ADMIN"]})

        self.assertTrue(result.success)
        self.assertEqual(result.data["ticket"]["assignee_name"], "张工")
        self.assertEqual(result.data["ticket"]["reporter_name"], "李业主")
        self.assertIn("resolveDueAt", result.data["sla"])

    async def test_invalid_assignee_rejected(self):
        gateway = FakeGateway()
        executor = ToolExecutorImpl(FakeRegistry(spec()), gateway)

        result = await executor.execute(
            ToolCall("c1", "ticket_query", {"assignee_id": "张三"}),
            {"actor_id": 1, "roles": ["ADMIN"]})

        self.assertFalse(result.success)
        self.assertEqual(result.error_code, "tool_invalid_arguments")
        self.assertEqual(gateway.calls, 0)


class JavaInternalErrorTest(unittest.TestCase):

    def response(self, status_code: int, body: dict) -> httpx.Response:
        return httpx.Response(status_code, json=body,
                              request=httpx.Request("GET", "http://test"))

    def test_http_forbidden(self):
        with self.assertRaises(JavaInternalError) as ctx:
            JavaInternalClient._unwrap(self.response(403, {"code": 10003, "message": "no"}))
        self.assertEqual(ctx.exception.error_code, "tool_forbidden")

    def test_business_not_found(self):
        with self.assertRaises(JavaInternalError) as ctx:
            JavaInternalClient._unwrap(self.response(200, {"code": 20001, "message": "no"}))
        self.assertEqual(ctx.exception.error_code, "tool_not_found")

    def test_business_validation(self):
        with self.assertRaises(JavaInternalError) as ctx:
            JavaInternalClient._unwrap(self.response(200, {"code": 60001, "message": "bad"}))
        self.assertEqual(ctx.exception.error_code, "tool_invalid_arguments")

    def test_server_error_is_transient(self):
        with self.assertRaises(JavaInternalError) as ctx:
            JavaInternalClient._unwrap(self.response(500, {"message": "boom"}))
        self.assertEqual(ctx.exception.error_code, "tool_failed")


if __name__ == "__main__":
    unittest.main()
