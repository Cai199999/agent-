"""对话应用服务：提示词组装、工具编排与流式事件生产。

Python 无状态：会话历史、知识片段均由 Java 传入，本层不读写数据库。
只读工具经 Java 内部 API 执行；写工具只生成待确认草稿。
"""

import json
import logging
import time
from collections.abc import AsyncIterator

from domain.chat import ChatCommand
from domain.tool import ToolCall

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """你是「IT 运维工单智能助手」，服务于物业与 IT 运维场景。

回答规则：
1. 优先依据下方【知识片段】回答；引用片段时在句末标注编号，如 [1][2]。
2. 知识片段不足或相互矛盾时，明确说明“知识库暂无依据”，不要编造设备参数、电话、流程或法规条款。
3. 面向报障人时，回答简洁、分步骤、可执行；先给安全止损，再给处理步骤，最后说明何时需要转人工。
4. 涉及困人、漏电、火情、燃气等安全风险，必须先提示现场安全措施与报警/急救电话。
5. 使用简体中文；除用户要求外不要输出与问题无关的寒暄。

工具使用规则：
- 用户询问“我的工单/进度/处理到哪了/最近报修”时，调用 ticket_query；需要细节时再调用 ticket_detail。
- 用户询问“以前有没有类似问题/历史上怎么处理”时，调用 ticket_similar 检索历史相似工单。
- 需要引用规范、流程或知识文档时，调用 knowledge_search 检索知识库。
- 用户明确报修且已提供位置、设备或故障现象时，可调用 ticket_create 生成草稿；草稿必须由用户在界面上确认后才会真正建单。
- 信息不足时先追问，不要调用 ticket_create。
- 不得伪造工具调用结果；工具失败时如实说明并给出替代建议。"""


class ChatService:
    def __init__(self, llm_client, max_context_chunks: int = 8,
                 tool_registry=None, tool_executor=None, max_steps: int = 3,
                 tool_result_max_chars: int = 4000):
        self.llm_client = llm_client
        self.max_context_chunks = max_context_chunks
        self.tool_registry = tool_registry
        self.tool_executor = tool_executor
        self.max_steps = max(1, max_steps)
        self.tool_result_max_chars = max(256, tool_result_max_chars)

    async def stream(self, command: ChatCommand) -> AsyncIterator[tuple[str, dict]]:
        chunks = command.context_chunks[: self.max_context_chunks]
        context_chars = sum(len(chunk.content or "") for chunk in chunks)
        logger.info(
            "chat.context trace=%s session=%s history=%d chunks=%d context_chars=%d max_chunks=%d",
            command.trace_id,
            command.session_id,
            len(command.messages),
            len(chunks),
            context_chars,
            self.max_context_chunks,
        )
        yield "meta", {
            "trace_id": command.trace_id,
            "session_id": command.session_id,
            "assistant_message_id": command.assistant_message_id,
            "model": command.model or getattr(self.llm_client, "model", ""),
            "degraded": not chunks,
        }
        for chunk in chunks:
            yield "citation", {
                "doc_id": chunk.doc_id,
                "chunk_id": chunk.chunk_id,
                "title": chunk.title,
                "score": chunk.score,
                "rank": chunk.rank,
            }

        messages = self._build_messages(command, chunks)
        tools = None
        if self.tool_registry is not None and self.tool_executor is not None:
            tools = self.tool_registry.openai_tools()
        actor = {"actor_id": command.actor_id, "roles": command.roles}

        started = time.perf_counter()
        token_in = 0
        token_out = 0
        finish_reason = "stop"
        try:
            completed = False
            for step in range(self.max_steps):
                assistant_parts: list[str] = []
                tool_calls: list[dict] = []
                async for delta in self.llm_client.stream(messages, tools=tools):
                    if delta.content:
                        assistant_parts.append(delta.content)
                        yield "delta", {"content": delta.content}
                    if delta.input_tokens:
                        token_in = delta.input_tokens
                    if delta.output_tokens:
                        token_out = delta.output_tokens
                    if delta.finish_reason:
                        finish_reason = delta.finish_reason
                    if delta.tool_calls:
                        tool_calls.extend(delta.tool_calls)

                if not tool_calls:
                    completed = True
                    break

                messages.append({
                    "role": "assistant",
                    "content": "".join(assistant_parts) or None,
                    "tool_calls": [
                        {
                            "id": call.get("id") or f"call_{step}_{index}",
                            "type": "function",
                            "function": {
                                "name": call.get("name") or "",
                                "arguments": call.get("arguments") or "{}",
                            },
                        }
                        for index, call in enumerate(tool_calls)
                    ],
                })

                for index, call in enumerate(tool_calls):
                    call_id = call.get("id") or f"call_{step}_{index}"
                    name = call.get("name") or ""
                    arguments = self._parse_arguments(call.get("arguments"))
                    argument_error = arguments is None
                    if argument_error:
                        arguments = {}
                        result = None
                        status = "FAILED"
                        message = "工具参数不是合法 JSON"
                    else:
                        result = await self.tool_executor.execute(
                            ToolCall(call_id, name, arguments), actor, command.trace_id)
                        status = "SUCCESS" if result.success else "FAILED"
                        message = result.message or ""
                        if result.success and (result.data or {}).get("state") == "CONFIRM_REQUIRED":
                            status = "CONFIRM_REQUIRED"

                    tool_payload = {}
                    if result is not None:
                        tool_payload = result.data if result.success else {
                            "error_code": result.error_code,
                            "message": result.message,
                        }
                    raw_payload_text = json.dumps(tool_payload or {}, ensure_ascii=False)
                    result_truncated = len(raw_payload_text) > self.tool_result_max_chars
                    spec = self.tool_registry.get(name)
                    yield "tool_call", {
                        "tool_call_id": call_id,
                        "name": name,
                        "status": status,
                        "arguments": arguments,
                        "arguments_summary": self.tool_executor.summarize(name, arguments),
                        "requires_confirm": bool(spec and spec.requires_confirm),
                        "result_summary": message,
                        "result_ticket_id": (result.data or {}).get("ticket_id") if result else None,
                        "result_size_bytes": len(raw_payload_text.encode("utf-8")),
                        "result_truncated": result_truncated,
                        "error_code": result.error_code if result else "tool_invalid_arguments",
                    }

                    messages.append({
                        "role": "tool",
                        "tool_call_id": call_id,
                        "content": self._tool_payload_content(
                            tool_payload, command.trace_id, name),
                    })

            if not completed:
                logger.warning(
                    "chat.step_limit trace=%s session=%s max_steps=%d",
                    command.trace_id,
                    command.session_id,
                    self.max_steps,
                )
                yield "error", {
                    "code": "ai_agent_step_limit",
                    "message": "工具调用超过最大步骤",
                    "retryable": False,
                }
                return
        except Exception as exc:  # noqa: BLE001 - 统一转为 SSE error 事件
            logger.warning(
                "chat.error trace=%s session=%s code=%s reason=%s",
                command.trace_id,
                command.session_id,
                self._map_error(exc),
                exc,
            )
            yield "error", {
                "code": self._map_error(exc),
                "message": "模型生成失败",
                "retryable": True,
            }
            return

        latency_ms = int((time.perf_counter() - started) * 1000)
        logger.info(
            "chat.done trace=%s session=%s latency_ms=%d input_tokens=%d output_tokens=%d finish=%s",
            command.trace_id,
            command.session_id,
            latency_ms,
            token_in,
            token_out,
            finish_reason,
        )
        yield "done", {
            "finish_reason": finish_reason,
            "usage": {"input_tokens": token_in, "output_tokens": token_out},
            "latency_ms": latency_ms,
        }

    def _tool_payload_content(self, payload: dict | None,
                              trace_id: str | None = None,
                              tool_name: str | None = None) -> str:
        """工具结果确定性硬截断：保持合法 JSON，避免超长结果撑爆上下文。"""
        text = json.dumps(payload or {}, ensure_ascii=False)
        if len(text) <= self.tool_result_max_chars:
            return text
        logger.warning(
            "tool_result.truncated trace=%s tool=%s original_chars=%d limit=%d",
            trace_id,
            tool_name,
            len(text),
            self.tool_result_max_chars,
        )
        return json.dumps({
            "truncated": True,
            "original_chars": len(text),
            "note": f"工具结果超过 {self.tool_result_max_chars} 字符，仅保留预览",
            "preview": text[: self.tool_result_max_chars],
        }, ensure_ascii=False)

    def _build_messages(self, command: ChatCommand, chunks) -> list[dict]:
        system = SYSTEM_PROMPT + "\n\n" + self._render_context(chunks)
        messages: list[dict] = [{"role": "system", "content": system}]
        for message in command.messages[-24:]:
            content = (message.content or "").strip()
            if not content:
                continue
            if len(content) > 4000:
                content = content[:4000] + "…"
            messages.append({"role": message.role, "content": content})
        return messages

    @staticmethod
    def _render_context(chunks) -> str:
        if not chunks:
            return "【知识片段】无。请基于通用运维常识谨慎回答，并提示用户补充设备、位置与现象。"
        lines = ["【知识片段】"]
        for chunk in chunks:
            title = chunk.title or "未命名知识"
            lines.append(f"[{chunk.rank}] 标题：{title}（相似度 {chunk.score:.4f}）")
            lines.append(chunk.content.strip())
            lines.append("")
        return "\n".join(lines).strip()

    @staticmethod
    def _parse_arguments(raw) -> dict | None:
        if isinstance(raw, dict):
            return raw
        if not raw:
            return {}
        try:
            parsed = json.loads(raw)
        except (TypeError, ValueError):
            return None
        return parsed if isinstance(parsed, dict) else None

    @staticmethod
    def _map_error(exc: Exception) -> str:
        name = type(exc).__name__.lower()
        if "timeout" in name:
            return "ai_llm_timeout"
        if "connection" in name:
            return "ai_unavailable"
        return "ai_unavailable"
