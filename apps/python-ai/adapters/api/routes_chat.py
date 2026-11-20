"""Agent 对话内部 API（SSE）。"""

import json

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse

from adapters.api.dependencies import require_internal_token
from adapters.api.schemas import ChatRequest
from adapters.gateways.java_internal_client import JavaInternalClient
from adapters.models.llm_deepseek import DeepSeekChatClient
from app.chat_service import ChatService
from app.tool_executor import ToolExecutorImpl
from app.tool_registry import ToolRegistryImpl
from config import settings
from domain.chat import ChatCommand, ChatMessage, ContextChunk

router = APIRouter(
    prefix="/v1/agent",
    tags=["agent"],
    dependencies=[Depends(require_internal_token)],
)

_service = None


def get_service() -> ChatService:
    global _service
    if _service is None:
        registry = ToolRegistryImpl()
        executor = ToolExecutorImpl(registry, JavaInternalClient())
        _service = ChatService(
            DeepSeekChatClient(),
            settings.chat_max_context_chunks,
            registry,
            executor,
            settings.agent_max_steps,
            settings.tool_result_max_chars,
            settings.agent_plan_events,
            settings.agent_tool_retry_max,
        )
    return _service


@router.post("/chat")
async def chat(request: ChatRequest, http_request: Request) -> StreamingResponse:
    command = ChatCommand(
        trace_id=request.trace_id or getattr(http_request.state, "trace_id", None),
        session_id=request.session_id,
        assistant_message_id=request.assistant_message_id,
        model=request.model,
        messages=[ChatMessage(role=item.role, content=item.content) for item in request.messages],
        context_chunks=[
            ContextChunk(
                doc_id=item.doc_id,
                chunk_id=item.chunk_id,
                title=item.title,
                content=item.content,
                score=item.score,
                rank=item.rank,
            )
            for item in request.context_chunks
        ],
        actor_id=request.actor.actor_id if request.actor else None,
        roles=request.actor.roles if request.actor else [],
    )

    async def event_stream():
        event_id = 0
        try:
            async for event, data in get_service().stream(command):
                event_id += 1
                yield _encode(event_id, event, data)
        except Exception:  # noqa: BLE001 - 流已开始，只能以 error 事件结束
            event_id += 1
            yield _encode(event_id, "error", {
                "code": "ai_unavailable",
                "message": "AI 服务异常",
                "retryable": True,
            })

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


def _encode(event_id: int, event: str, data: dict) -> str:
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return f"id: {event_id}\nevent: {event}\ndata: {payload}\n\n"
