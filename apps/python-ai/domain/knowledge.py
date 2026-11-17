"""知识库领域模型。"""

from dataclasses import dataclass


@dataclass(frozen=True)
class KnowledgeChunk:
    doc_id: int
    doc_version: int
    chunk_no: int
    content: str
    vector_id: str
    content_hash: str
    category_code: str
    visibility_type: str


@dataclass(frozen=True)
class TicketVectorHit:
    ticket_id: int
    ticket_no: str
    title: str
    status_code: str
    ticket_type_code: str
    score: float


@dataclass(frozen=True)
class RetrievalHit:
    doc_id: int
    doc_version: int
    chunk_no: int
    vector_id: str
    score: float
    category_code: str
