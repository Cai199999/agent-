"""向量索引与相似工单内部 API。"""

from fastapi import APIRouter, Depends, HTTPException

from adapters.api.dependencies import require_internal_token
from adapters.api.schemas import (
    SimilarTicketHit,
    SimilarTicketRequest,
    SimilarTicketResponse,
    VectorDeleteRequest,
    VectorDeleteResponse,
    VectorIndexRequest,
    VectorIndexResponse,
)
from adapters.models.embedding_openai_compatible import OpenAICompatibleEmbeddingClient
from adapters.stores.milvus_knowledge_store import MilvusKnowledgeStore
from adapters.stores.milvus_ticket_store import MilvusTicketStore
from app.ticket_vector_service import TicketVectorService

router = APIRouter(tags=["vector"], dependencies=[Depends(require_internal_token)])

_service = None
_knowledge_store = None


def get_knowledge_store() -> MilvusKnowledgeStore:
    global _knowledge_store
    if _knowledge_store is None:
        _knowledge_store = MilvusKnowledgeStore()
    return _knowledge_store


def get_service() -> TicketVectorService:
    global _service
    if _service is None:
        _service = TicketVectorService(OpenAICompatibleEmbeddingClient(), MilvusTicketStore())
    return _service


@router.post("/v1/vector/index", response_model=VectorIndexResponse)
def index(request: VectorIndexRequest) -> VectorIndexResponse:
    if request.object_type.upper() != "TICKET":
        raise HTTPException(status_code=400, detail="only TICKET is supported")
    items = [
        {
            "ticket_id": item.ticket_id,
            "ticket_no": item.ticket_no,
            "ticket_version": item.ticket_version,
            "title": item.title,
            "description": item.description,
            "solution": item.solution,
            "status_code": item.status_code,
            "ticket_type_code": item.ticket_type_code,
        }
        for item in request.items
    ]
    try:
        indexed = get_service().index(items)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=502, detail="ticket vector index failed") from exc
    return VectorIndexResponse(object_type="TICKET", indexed=indexed)


@router.post("/v1/ai/similar/tickets", response_model=SimilarTicketResponse)
def similar_tickets(request: SimilarTicketRequest) -> SimilarTicketResponse:
    try:
        hits = get_service().similar(request.query, request.top_k, request.exclude_ticket_id)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=503, detail="ticket vector search failed") from exc
    return SimilarTicketResponse(hits=[
        SimilarTicketHit(
            ticket_id=hit.ticket_id,
            ticket_no=hit.ticket_no,
            title=hit.title,
            status_code=hit.status_code,
            ticket_type_code=hit.ticket_type_code,
            score=hit.score,
        )
        for hit in hits
    ])


@router.post("/v1/vector/delete", response_model=VectorDeleteResponse)
def delete_vectors(request: VectorDeleteRequest) -> VectorDeleteResponse:
    if request.object_type.upper() != "KB_CHUNK":
        raise HTTPException(status_code=400, detail="only KB_CHUNK is supported")
    try:
        deleted = get_knowledge_store().delete(request.vector_ids)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=502, detail="vector delete failed") from exc
    return VectorDeleteResponse(deleted=deleted)
