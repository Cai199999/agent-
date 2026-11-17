"""端口定义。

后续阶段在此定义 LLMClient、VectorStore、TicketGateway、
ContextProvider、KnowledgeGateway 等 Protocol；领域层/应用层不得直接依赖 SDK。

当前仅预留工具注册/执行与知识库端口，不提供实现。
"""

from typing import Any, AsyncIterator, Protocol, Sequence

from domain.chat import LLMDelta
from domain.knowledge import KnowledgeChunk, RetrievalHit
from domain.tool import ToolCall, ToolResult, ToolSpec


class ToolRegistry(Protocol):
    def get(self, name: str) -> ToolSpec | None: ...

    def list_tools(self) -> Sequence[ToolSpec]: ...


class ToolExecutor(Protocol):
    async def execute(self, call: ToolCall, actor: dict[str, Any]) -> ToolResult: ...


class EmbeddingClient(Protocol):
    def embed(self, texts: Sequence[str]) -> list[list[float]]: ...


class VectorStore(Protocol):
    def upsert(self, chunks: Sequence[KnowledgeChunk], vectors: Sequence[Sequence[float]]) -> None: ...

    def search(self, vector: Sequence[float], top_k: int,
               category_code: str | None = None) -> list[RetrievalHit]: ...


class LLMClient(Protocol):
    def stream(self, messages: Sequence[dict[str, str]]) -> AsyncIterator[LLMDelta]: ...


class LLMJsonClient(Protocol):
    async def complete_json(self, messages: Sequence[dict[str, str]]) -> str: ...
