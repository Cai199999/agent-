"""Python → Java 内部 API 客户端（P2J Token）。

只读工具通过该网关访问业务数据；Python 不直连数据库。
"""

import httpx

from config import settings


class JavaInternalError(Exception):
    pass


class JavaInternalClient:
    def __init__(self) -> None:
        self._client = httpx.AsyncClient(
            base_url=settings.java_base_url,
            timeout=3.0,
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
                           trace_id: str | None = None) -> dict:
        params = {"limit": max(1, min(limit, 10))}
        if status:
            params["status"] = status
        if keyword:
            params["keyword"] = keyword
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

    async def get_ticket(self, actor: dict, ticket_id: str,
                         trace_id: str | None = None) -> dict:
        response = await self._client.get(
            f"/internal/v1/tickets/{ticket_id}", headers=self._headers(actor, trace_id))
        return self._unwrap(response)

    @staticmethod
    def _unwrap(response: httpx.Response) -> dict:
        if response.status_code != 200:
            raise JavaInternalError(f"java internal http {response.status_code}")
        body = response.json()
        if body.get("code") != 0:
            raise JavaInternalError(body.get("message") or "java internal error")
        return body.get("data") or {}
