"""有界任务拆解与工具重试的确定性单元测试。

不连接 LLM / Java / MySQL：用假客户端验证 plan/step 事件与幂等重试策略。
运行：cd apps/python-ai && ./.venv/bin/python -m unittest discover -s tests -v
"""

from __future__ import annotations

import asyncio
import unittest

from app.chat_service import ChatService
from domain.chat import ChatCommand, ChatMessage, LLMDelta
from domain.tool import ToolResult, ToolRiskLevel, ToolSpec


class FakeLlm:
    def __init__(self, calls_per_step: int = 1) -> None:
        self.calls = 0
        self.calls_per_step = calls_per_step

    async def stream(self, messages, tools=None):
        self.calls += 1
        if self.calls == 1:
            yield LLMDelta(
                tool_calls=[{
                    "id": f"call-{index}",
                    "name": "ticket_query",
                    "arguments": "{\"limit\": 1}",
                } for index in range(self.calls_per_step)],
                finish_reason="tool_calls",
            )
            return
        yield LLMDelta(content="查询完成", finish_reason="stop")


class FakeRegistry:
    def __init__(self, idempotent: bool = True) -> None:
        self.spec = ToolSpec(
            name="ticket_query",
            description="query",
            input_schema={"type": "object"},
            output_schema={"type": "object"},
            permission="ticket:read",
            risk_level=ToolRiskLevel.LOW,
            idempotent=idempotent,
            max_retries=1,
        )

    def get(self, name):
        return self.spec if name == self.spec.name else None

    def openai_tools(self):
        return []


class FakeExecutor:
    def __init__(self, fail_times: int = 1, fail_code: str = "tool_failed",
                 delay: float = 0.0) -> None:
        self.fail_times = fail_times
        self.fail_code = fail_code
        self.delay = delay
        self.calls = 0
        self.active = 0
        self.max_active = 0

    async def execute(self, call, actor, trace_id=None):
        self.calls += 1
        self.active += 1
        self.max_active = max(self.max_active, self.active)
        try:
            if self.delay:
                await asyncio.sleep(self.delay)
            if self.calls <= self.fail_times:
                return ToolResult(call.tool_call_id, False, error_code=self.fail_code,
                                  message="临时失败")
            return ToolResult(call.tool_call_id, True, data={"count": 1, "tickets": []},
                              message="找到 1 条工单")
        finally:
            self.active -= 1

    def summarize(self, name, arguments):
        return f"{name}({arguments})"


def command() -> ChatCommand:
    return ChatCommand(
        trace_id="trace-test",
        session_id=1,
        assistant_message_id=2,
        model="fake",
        messages=[ChatMessage(role="user", content="查一下我的工单")],
        context_chunks=[],
        actor_id=10001,
        roles=["ADMIN"],
    )


class ChatPlanRetryTest(unittest.IsolatedAsyncioTestCase):

    async def collect(self, service: ChatService) -> list[tuple[str, dict]]:
        return [event async for event in service.stream(command())]

    async def test_plan_and_step_events_emitted(self):
        executor = FakeExecutor(fail_times=0)
        service = ChatService(FakeLlm(), tool_registry=FakeRegistry(),
                              tool_executor=executor, plan_events=True,
                              tool_retry_max=1)

        events = await self.collect(service)
        names = [name for name, _ in events]

        self.assertIn("plan", names)
        self.assertIn("step", names)
        plan = next(data for name, data in events if name == "plan")
        self.assertEqual(plan["step"], 1)
        self.assertEqual(plan["max_steps"], 3)
        self.assertEqual(plan["actions"][0]["tool"], "ticket_query")
        step = next(data for name, data in events if name == "step")
        self.assertEqual(step["status"], "SUCCESS")
        self.assertEqual(step["attempts"], 1)

    async def test_idempotent_tool_retries_once(self):
        executor = FakeExecutor(fail_times=1)
        service = ChatService(FakeLlm(), tool_registry=FakeRegistry(idempotent=True),
                              tool_executor=executor, plan_events=True,
                              tool_retry_max=1)

        events = await self.collect(service)
        step = next(data for name, data in events if name == "step")

        self.assertEqual(executor.calls, 2)
        self.assertEqual(step["status"], "SUCCESS")
        self.assertEqual(step["attempts"], 2)

    async def test_non_idempotent_tool_not_retried(self):
        executor = FakeExecutor(fail_times=1)
        service = ChatService(FakeLlm(), tool_registry=FakeRegistry(idempotent=False),
                              tool_executor=executor, plan_events=True,
                              tool_retry_max=1)

        events = await self.collect(service)
        step = next(data for name, data in events if name == "step")

        self.assertEqual(executor.calls, 1)
        self.assertEqual(step["status"], "FAILED")
        self.assertEqual(step["attempts"], 1)

    async def test_idempotent_tools_run_concurrently(self):
        executor = FakeExecutor(fail_times=0, delay=0.02)
        service = ChatService(FakeLlm(calls_per_step=2),
                              tool_registry=FakeRegistry(idempotent=True),
                              tool_executor=executor, plan_events=True,
                              tool_retry_max=1)

        events = await self.collect(service)
        steps = [data for name, data in events if name == "step"]

        self.assertEqual(executor.max_active, 2)
        self.assertEqual([item["action_index"] for item in steps], [0, 1])
        self.assertTrue(all(item["status"] == "SUCCESS" for item in steps))

    async def test_non_idempotent_tools_stay_sequential(self):
        executor = FakeExecutor(fail_times=0, delay=0.01)
        service = ChatService(FakeLlm(calls_per_step=2),
                              tool_registry=FakeRegistry(idempotent=False),
                              tool_executor=executor, plan_events=True,
                              tool_retry_max=1)

        events = await self.collect(service)
        steps = [data for name, data in events if name == "step"]

        self.assertEqual(executor.max_active, 1)
        self.assertEqual([item["action_index"] for item in steps], [0, 1])

    async def test_timeout_is_retried_once(self):
        executor = FakeExecutor(fail_times=1, fail_code="tool_timeout")
        service = ChatService(FakeLlm(), tool_registry=FakeRegistry(idempotent=True),
                              tool_executor=executor, plan_events=True,
                              tool_retry_max=1)

        events = await self.collect(service)
        step = next(data for name, data in events if name == "step")

        self.assertEqual(executor.calls, 2)
        self.assertEqual(step["status"], "SUCCESS")
        self.assertEqual(step["attempts"], 2)

    async def test_forbidden_is_not_retried(self):
        executor = FakeExecutor(fail_times=1, fail_code="tool_forbidden")
        service = ChatService(FakeLlm(), tool_registry=FakeRegistry(idempotent=True),
                              tool_executor=executor, plan_events=True,
                              tool_retry_max=1)

        events = await self.collect(service)
        step = next(data for name, data in events if name == "step")

        self.assertEqual(executor.calls, 1)
        self.assertEqual(step["status"], "FAILED")
        self.assertEqual(step["error_code"], "tool_forbidden")


if __name__ == "__main__":
    unittest.main()
