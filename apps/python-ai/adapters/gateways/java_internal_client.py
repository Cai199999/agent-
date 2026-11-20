"""Python → Java 内部 API 客户端（P2J Token）。

只读工具通过该网关访问业务数据；Python 不直连数据库。
"""

import httpx

from config import settings


class JavaInternalError(Exception):
    """内部接口错误；error_code 用于区分“可重试”和“不可重试”。"""

    def __init__(self, message: str, error_code: str = "tool_failed") -> None:
        super().__init__(message)
        self.error_code = error_code


class JavaInternalClient:
    def __init__(self) -> None:
        self._client = httpx.AsyncClient(
            base_url=settings.java_base_url,
            # 传输层超时只做兜底；真正的单工具超时由 ToolSpec.timeout_seconds 控制。
            timeout=httpx.Timeout(settings.tool_http_timeout_seconds, connect=3.0),
        )

    def _headers(self, actor: dict, trace_id: str | None) -> dict[str, str]:
        roles = actor.get("roles") or []
        return {
            "X-Internal-Token": settings.internal_token_p2j,
            "X-Actor-Id": str(actor.get("actor_id") or ""),
            "X-Actor-Roles": ",".join(str(role) for role in roles),
            "X-Trace-Id": trace_id or "",
        }

    async def list_tickets(self, actor: dict, status: str | None = None,
                           keyword: str | None = None, limit: int = 5,
                           priority: str | None = None, assignee_id: str | None = None,
                           start_time: str | None = None, end_time: str | None = None,
                           open_only: bool = False,
                           trace_id: str | None = None) -> dict:
        params = {"limit": max(1, min(limit, 10))}
        if status:
            params["status"] = status
        if keyword:
            params["keyword"] = keyword
        if priority:
            params["priority"] = priority
        if assignee_id:
            params["assigneeId"] = assignee_id
        if start_time:
            params["startTime"] = start_time
        if end_time:
            params["endTime"] = end_time
        if open_only:
            params["openOnly"] = "true"
        response = await self._client.get(
            "/internal/v1/tickets", params=params, headers=self._headers(actor, trace_id))
        data = self._unwrap(response)
        if isinstance(data, list):
            return {"items": data, "total": len(data)}
        return data

    async def search_knowledge(self, actor: dict, query: str, top_k: int = 5,
                               trace_id: str | None = None) -> dict:
        response = await self._client.post(
            "/internal/v1/knowledge/search",
            json={"query": query, "topK": max(1, min(top_k, 10))},
            headers=self._headers(actor, trace_id),
        )
        data = self._unwrap(response)
        items = data if isinstance(data, list) else (data.get("items") or [])
        return {"items": items, "total": len(items)}

    async def similar_tickets(self, actor: dict, query: str, top_k: int = 5,
                              trace_id: str | None = None) -> dict:
        response = await self._client.get(
            "/internal/v1/tickets/similar",
            params={"query": query, "top_k": max(1, min(top_k, 10))},
            headers=self._headers(actor, trace_id),
        )
        data = self._unwrap(response)
        items = data if isinstance(data, list) else (data.get("items") or [])
        return {"items": items, "total": len(items)}

    async def lookup_users(self, actor: dict, keyword: str, limit: int = 10,
                           trace_id: str | None = None) -> dict:
        response = await self._client.get(
            "/internal/v1/users",
            params={"keyword": keyword, "limit": max(1, min(limit, 20))},
            headers=self._headers(actor, trace_id),
        )
        data = self._unwrap(response)
        items = data if isinstance(data, list) else (data.get("items") or [])
        return {"items": items, "total": len(items)}

    async def get_ticket(self, actor: dict, ticket_id: str,
                         trace_id: str | None = None) -> dict:
        response = await self._client.get(
            f"/internal/v1/tickets/{ticket_id}", headers=self._headers(actor, trace_id))
        return self._unwrap(response)

    @classmethod
    def _unwrap(cls, response: httpx.Response) -> dict:
        body: dict = {}
        try:
            parsed = response.json()
            if isinstance(parsed, dict):
                body = parsed
        except ValueError:
            pass
        biz_code = body.get("code")
        if response.status_code != 200:
            raise JavaInternalError(
                body.get("message") or f"java internal http {response.status_code}",
                cls._error_code(response.status_code, biz_code),
            )
        if biz_code != 0:
            raise JavaInternalError(
                body.get("message") or "java internal error",
                cls._error_code(response.status_code, biz_code),
            )
        return body.get("data") or {}

    @staticmethod
    def _error_code(http_status: int, biz_code: object) -> str:
        """把 Java 错误翻译成工具层可判断的类别，避免把权限错误当成瞬时失败重试。"""
        if biz_code == 10003:
            return "tool_forbidden"
        if biz_code == 20001:
            return "tool_not_found"
        if biz_code in (60001, 60002, 60003):
            return "tool_invalid_arguments"
        if http_status in (401, 403):
            return "tool_forbidden"
        if http_status == 404:
            return "tool_not_found"
        if http_status in (400, 422):
            return "tool_invalid_arguments"
        return "tool_failed"
