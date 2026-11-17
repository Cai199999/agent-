"""trace_id 中间件。

读取/生成 X-Trace-Id，写入 request.state 与响应头；后续日志适配器统一使用。
"""

import uuid

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request


class TraceMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        trace_id = request.headers.get("X-Trace-Id") or uuid.uuid4().hex
        request.state.trace_id = trace_id
        response = await call_next(request)
        response.headers["X-Trace-Id"] = trace_id
        return response
