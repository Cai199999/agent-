"""分诊内部 API（同步）。"""

from fastapi import APIRouter, Depends, HTTPException

from adapters.api.dependencies import require_internal_token
from adapters.api.schemas import TriageDraftOut, TriageRequest, TriageResponse
from adapters.models.llm_deepseek import DeepSeekChatClient
from app.triage_service import TriageService
from config import settings

router = APIRouter(
    prefix="/v1/ai",
    tags=["triage"],
    dependencies=[Depends(require_internal_token)],
)

_service = None


def get_service() -> TriageService:
    global _service
    if _service is None:
        _service = TriageService(
            DeepSeekChatClient(),
            confidence_threshold=settings.triage_confidence_threshold,
            timeout_seconds=settings.triage_timeout_seconds,
            prompt_version=settings.triage_prompt_version,
            max_history=settings.triage_max_history,
        )
    return _service


@router.post("/triage", response_model=TriageResponse)
async def triage(request: TriageRequest) -> TriageResponse:
    try:
        result = await get_service().triage(
            [{"role": item.role, "content": item.content} for item in request.messages]
        )
    except TimeoutError as exc:
        raise HTTPException(status_code=504, detail="triage timeout") from exc
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=502, detail="triage failed") from exc

    draft = result.get("draft")
    return TriageResponse(
        intent_code=result["intent_code"],
        confidence=result["confidence"],
        entities=result.get("entities") or {},
        action=result["action"],
        low_confidence=result["low_confidence"],
        draft=TriageDraftOut(**draft) if draft else None,
        model=result.get("model", ""),
        prompt_version=result.get("prompt_version", "v1"),
        latency_ms=result.get("latency_ms", 0),
    )
