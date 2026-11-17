"""工单 AI 初步分析内部 API。"""

from fastapi import APIRouter, Depends, HTTPException

from adapters.api.dependencies import require_internal_token
from adapters.api.schemas import TicketAnalysisRequest, TicketAnalysisResponse
from adapters.models.llm_deepseek import DeepSeekChatClient
from app.ticket_analysis_service import TicketAnalysisService

router = APIRouter(
    prefix="/v1/ai",
    tags=["analysis"],
    dependencies=[Depends(require_internal_token)],
)

_service = None


def get_service() -> TicketAnalysisService:
    global _service
    if _service is None:
        _service = TicketAnalysisService(DeepSeekChatClient())
    return _service


@router.post("/ticket-analysis", response_model=TicketAnalysisResponse)
async def ticket_analysis(request: TicketAnalysisRequest) -> TicketAnalysisResponse:
    ticket = {
        "ticket_no": request.ticket.ticket_no,
        "title": request.ticket.title,
        "description": request.ticket.description,
        "ticket_type_code": request.ticket.ticket_type_code,
        "priority_code": request.ticket.priority_code,
        "status_code": request.ticket.status_code,
        "location_text": request.ticket.location_text,
    }
    chunks = [
        {
            "doc_id": item.doc_id,
            "chunk_id": item.chunk_id,
            "title": item.title,
            "content": item.content,
            "score": item.score,
            "rank": item.rank,
        }
        for item in request.context_chunks
    ]
    try:
        result = await get_service().analyze(ticket, chunks)
    except TimeoutError as exc:
        raise HTTPException(status_code=504, detail="analysis timeout") from exc
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=502, detail="analysis failed") from exc
    return TicketAnalysisResponse(**result)
