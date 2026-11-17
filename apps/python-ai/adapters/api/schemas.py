"""知识库 API 请求/响应模型。"""

from pydantic import BaseModel, Field


class IngestRequest(BaseModel):
    doc_id: int
    doc_version: int = 1
    title: str
    category_code: str
    visibility_type: str = "PUBLIC"
    text: str


class ChunkOut(BaseModel):
    chunk_no: int
    content: str
    vector_id: str
    content_hash: str


class IngestResponse(BaseModel):
    doc_id: int
    doc_version: int
    chunk_count: int
    chunks: list[ChunkOut]


class SearchRequest(BaseModel):
    query: str = Field(min_length=1)
    top_k: int = Field(default=5, ge=1, le=20)
    category_code: str | None = None


class HitOut(BaseModel):
    doc_id: int
    doc_version: int
    chunk_no: int
    vector_id: str
    score: float
    category_code: str


class SearchResponse(BaseModel):
    hits: list[HitOut]


class ChatTurnIn(BaseModel):
    role: str = Field(pattern="^(system|user|assistant)$")
    content: str


class ChatContextChunkIn(BaseModel):
    doc_id: int
    chunk_id: int
    title: str = ""
    content: str
    score: float = 0.0
    rank: int = 0


class ChatActorIn(BaseModel):
    actor_id: int | None = None
    roles: list[str] = Field(default_factory=list)


class ChatRequest(BaseModel):
    trace_id: str | None = None
    session_id: int
    assistant_message_id: int
    model: str | None = None
    messages: list[ChatTurnIn] = Field(min_length=1)
    context_chunks: list[ChatContextChunkIn] = Field(default_factory=list)
    actor: ChatActorIn | None = None


class TriageRequest(BaseModel):
    trace_id: str | None = None
    messages: list[ChatTurnIn] = Field(min_length=1)


class TriageDraftOut(BaseModel):
    title: str = ""
    description: str = ""
    ticket_type_code: str = "GENERAL"
    priority_code: str = "NORMAL"
    location_text: str = ""


class TriageResponse(BaseModel):
    intent_code: str
    confidence: float
    entities: dict = Field(default_factory=dict)
    action: str
    low_confidence: bool = False
    draft: TriageDraftOut | None = None
    model: str = ""
    prompt_version: str = "v1"
    latency_ms: int = 0


class TicketVectorItemIn(BaseModel):
    ticket_id: int
    ticket_no: str = ""
    ticket_version: int = 0
    title: str | None = None
    description: str | None = None
    solution: str | None = None
    status_code: str | None = None
    ticket_type_code: str | None = None


class VectorIndexRequest(BaseModel):
    object_type: str = "TICKET"
    items: list[TicketVectorItemIn] = Field(min_length=1)


class VectorIndexResponse(BaseModel):
    object_type: str
    indexed: int


class SimilarTicketRequest(BaseModel):
    query: str = Field(min_length=1)
    top_k: int = Field(default=5, ge=1, le=20)
    exclude_ticket_id: int | None = None


class SimilarTicketHit(BaseModel):
    ticket_id: int
    ticket_no: str
    title: str
    status_code: str
    ticket_type_code: str
    score: float


class SimilarTicketResponse(BaseModel):
    hits: list[SimilarTicketHit]


class VectorDeleteRequest(BaseModel):
    object_type: str = "KB_CHUNK"
    vector_ids: list[str] = Field(min_length=1)


class VectorDeleteResponse(BaseModel):
    deleted: int


class TicketInfoIn(BaseModel):
    ticket_no: str = ""
    title: str
    description: str | None = None
    ticket_type_code: str | None = None
    priority_code: str | None = None
    status_code: str | None = None
    location_text: str | None = None


class TicketAnalysisRequest(BaseModel):
    trace_id: str | None = None
    ticket: TicketInfoIn
    context_chunks: list[ChatContextChunkIn] = Field(default_factory=list)


class TicketAnalysisResponse(BaseModel):
    summary: str
    causes: list[str] = Field(default_factory=list)
    actions: list[str] = Field(default_factory=list)
    risk_level: str = "MEDIUM"
    model: str = ""
    prompt_version: str = "v1"
    latency_ms: int = 0
