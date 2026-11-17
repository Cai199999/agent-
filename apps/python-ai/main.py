"""Python AI 服务组装根。

当前提供健康检查、知识库入库/检索与 Agent 对话流。
"""

from fastapi import FastAPI

from adapters.api.middleware import TraceMiddleware
from adapters.api.routes_analysis import router as analysis_router
from adapters.api.routes_chat import router as chat_router
from adapters.api.routes_health import router as health_router
from adapters.api.routes_knowledge import router as knowledge_router
from adapters.api.routes_triage import router as triage_router
from adapters.api.routes_vector import router as vector_router


def create_app() -> FastAPI:
    app = FastAPI(
        title="agent-gongdan-python-ai",
        version="0.1.0",
        docs_url="/docs",
        openapi_url="/openapi.json",
    )
    app.add_middleware(TraceMiddleware)
    app.include_router(health_router)
    app.include_router(knowledge_router)
    app.include_router(chat_router)
    app.include_router(triage_router)
    app.include_router(vector_router)
    app.include_router(analysis_router)
    return app


app = create_app()
