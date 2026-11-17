"""知识库内部 API：入库与检索。"""

from fastapi import APIRouter, Depends, HTTPException

from adapters.api.dependencies import require_internal_token
from adapters.api.schemas import (
    ChunkOut,
    HitOut,
    IngestRequest,
    IngestResponse,
    SearchRequest,
    SearchResponse,
)
from adapters.models.embedding_openai_compatible import OpenAICompatibleEmbeddingClient
from adapters.stores.milvus_knowledge_store import MilvusKnowledgeStore
from app.service import KnowledgeService

router = APIRouter(
    prefix="/v1/knowledge",
    tags=["knowledge"],
    dependencies=[Depends(require_internal_token)],
)

_service = None


def get_service() -> KnowledgeService:
    global _service
    if _service is None:
        _service = KnowledgeService(OpenAICompatibleEmbeddingClient(), MilvusKnowledgeStore())
    return _service


@router.post("/ingest", response_model=IngestResponse)
def ingest(request: IngestRequest) -> IngestResponse:
    try:
        chunks = get_service().ingest(
            request.doc_id,
            request.doc_version,
            request.category_code,
            request.visibility_type,
            request.text,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return IngestResponse(
        doc_id=request.doc_id,
        doc_version=request.doc_version,
        chunk_count=len(chunks),
        chunks=[
            ChunkOut(
                chunk_no=chunk.chunk_no,
                content=chunk.content,
                vector_id=chunk.vector_id,
                content_hash=chunk.content_hash,
            )
            for chunk in chunks
        ],
    )


@router.post("/search", response_model=SearchResponse)
def search(request: SearchRequest) -> SearchResponse:
    hits = get_service().search(request.query, request.top_k, request.category_code)
    return SearchResponse(hits=[
        HitOut(
            doc_id=hit.doc_id,
            doc_version=hit.doc_version,
            chunk_no=hit.chunk_no,
            vector_id=hit.vector_id,
            score=hit.score,
            category_code=hit.category_code,
        )
        for hit in hits
    ])
