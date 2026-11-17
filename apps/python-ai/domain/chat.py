"""对话领域模型。"""

from dataclasses import dataclass, field


@dataclass(frozen=True)
class ChatMessage:
    role: str
    content: str


@dataclass(frozen=True)
class ContextChunk:
    doc_id: int
    chunk_id: int
    title: str
    content: str
    score: float
    rank: int


@dataclass(frozen=True)
class ChatCommand:
    trace_id: str | None
    session_id: int
    assistant_message_id: int
    model: str | None
    messages: list[ChatMessage]
    context_chunks: list[ContextChunk]
    actor_id: int | None = None
    roles: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class LLMDelta:
    content: str = ""
    finish_reason: str | None = None
    input_tokens: int = 0
    output_tokens: int = 0
    tool_calls: list[dict] | None = None
