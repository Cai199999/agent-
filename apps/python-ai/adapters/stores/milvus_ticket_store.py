"""工单历史向量存储适配器（Milvus Lite）。

每张工单一个向量：标题 + 描述 + 解决方案；vector_id 固定为 ticket_{id}，
更新时覆盖旧向量，避免同一工单多版本重复命中。
"""

import time

from pymilvus import CollectionSchema, DataType, FieldSchema
from pymilvus.milvus_client.index import IndexParams

from adapters.stores.milvus_client import get_milvus_client
from config import settings
from domain.knowledge import TicketVectorHit


class MilvusTicketStore:
    def __init__(self) -> None:
        self.collection_name = settings.milvus_collection_ticket
        self.metric_type = settings.milvus_metric_type
        self.client = get_milvus_client()
        self._ensure_collection()

    def _index_params(self) -> IndexParams:
        index_params = IndexParams()
        index_params.add_index(
            field_name="embedding",
            index_type="FLAT",
            metric_type=self.metric_type,
            params={},
        )
        return index_params

    def _ensure_collection(self) -> None:
        if self.client.has_collection(self.collection_name):
            if not self.client.list_indexes(self.collection_name):
                self.client.create_index(self.collection_name, self._index_params())
            self.client.load_collection(self.collection_name)
            return
        fields = [
            FieldSchema("vector_id", DataType.VARCHAR, is_primary=True, max_length=128),
            FieldSchema("embedding", DataType.FLOAT_VECTOR, dim=settings.embedding_dim),
            FieldSchema("ticket_id", DataType.INT64),
            FieldSchema("ticket_no", DataType.VARCHAR, max_length=32),
            FieldSchema("ticket_version", DataType.INT32),
            FieldSchema("title", DataType.VARCHAR, max_length=512),
            FieldSchema("status_code", DataType.VARCHAR, max_length=32),
            FieldSchema("ticket_type_code", DataType.VARCHAR, max_length=32),
            FieldSchema("updated_at", DataType.INT64),
        ]
        schema = CollectionSchema(fields, description="agent-gongdan ticket history")
        self.client.create_collection(
            collection_name=self.collection_name,
            schema=schema,
            index_params=self._index_params(),
        )
        self.client.load_collection(self.collection_name)

    def upsert_tickets(self, items, vectors) -> None:
        now = int(time.time() * 1000)
        rows = [
            {
                "vector_id": f"ticket_{item['ticket_id']}",
                "embedding": list(vector),
                "ticket_id": int(item["ticket_id"]),
                "ticket_no": str(item.get("ticket_no") or "")[:32],
                "ticket_version": int(item.get("ticket_version") or 0),
                "title": str(item.get("title") or "")[:512],
                "status_code": str(item.get("status_code") or "")[:32],
                "ticket_type_code": str(item.get("ticket_type_code") or "")[:32],
                "updated_at": now,
            }
            for item, vector in zip(items, vectors)
        ]
        self.client.upsert(collection_name=self.collection_name, data=rows)
        self.client.flush(self.collection_name)

    def search(self, vector, top_k: int, exclude_ticket_id: int | None = None) -> list[TicketVectorHit]:
        kwargs = {
            "collection_name": self.collection_name,
            "data": [list(vector)],
            "anns_field": "embedding",
            "limit": max(1, min(top_k, 20)),
            "output_fields": [
                "ticket_id", "ticket_no", "ticket_version", "title",
                "status_code", "ticket_type_code",
            ],
            "search_params": {"metric_type": self.metric_type, "params": {}},
        }
        if exclude_ticket_id:
            kwargs["filter"] = f"ticket_id != {int(exclude_ticket_id)}"

        results = self.client.search(**kwargs)
        hits: list[TicketVectorHit] = []
        for group in results:
            for hit in group:
                entity = hit.get("entity") if isinstance(hit, dict) else None
                entity = entity or hit
                hits.append(TicketVectorHit(
                    ticket_id=int(entity.get("ticket_id")),
                    ticket_no=str(entity.get("ticket_no")),
                    title=str(entity.get("title")),
                    status_code=str(entity.get("status_code")),
                    ticket_type_code=str(entity.get("ticket_type_code")),
                    score=float(hit.get("distance", hit.get("score", 0.0))),
                ))
        return hits
