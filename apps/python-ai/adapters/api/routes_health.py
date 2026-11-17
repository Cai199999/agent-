"""健康检查与就绪探针。"""

from fastapi import APIRouter

from config import settings

router = APIRouter()


@router.get("/health")
def health() -> dict:
    return {"status": "UP", "service": "python-ai", "env": settings.app_env}


@router.get("/ready")
def ready() -> dict:
    return {"status": "UP", "service": "python-ai", "ready": True}
