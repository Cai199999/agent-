"""工单历史向量：索引与相似检索。"""


class TicketVectorService:
    def __init__(self, embedding_client, store, max_text_chars: int = 2000):
        self.embedding_client = embedding_client
        self.store = store
        self.max_text_chars = max_text_chars

    def index(self, items: list[dict]) -> int:
        valid = [item for item in items if self._text(item)]
        if not valid:
            return 0
        vectors = self.embedding_client.embed([self._text(item) for item in valid])
        self.store.upsert_tickets(valid, vectors)
        return len(valid)

    def similar(self, query: str, top_k: int, exclude_ticket_id: int | None = None):
        normalized = (query or "").strip()
        if not normalized:
            raise ValueError("query is empty")
        vector = self.embedding_client.embed([normalized])[0]
        return self.store.search(vector, top_k, exclude_ticket_id)

    def _text(self, item: dict) -> str:
        parts = [
            str(item.get("title") or "").strip(),
            str(item.get("description") or "").strip(),
            str(item.get("solution") or "").strip(),
        ]
        text = "\n".join(part for part in parts if part)
        return text[: self.max_text_chars]
