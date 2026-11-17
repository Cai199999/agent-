"""Milvus Lite 知识向量存储适配器（MilvusClient 本地文件模式）。

Collection 命名：agent_gongdan_{type}_{model}_{dim}_v{version}
"""

import time

from pymilvus import CollectionSchema, DataType, FieldSchema
from pymilvus.milvus_client.index import IndexParams

from adapters.stores.milvus_client import get_milvus_client
from config import settings
from domain.knowledge import KnowledgeChunk, RetrievalHit


class MilvusKnowledgeStore:
    def __init__(self) -> None:
        self.collection_name = settings.milvus_collection_knowledge
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
            FieldSchema("doc_id", DataType.INT64),
            FieldSchema("doc_version", DataType.INT32),
            FieldSchema("chunk_no", DataType.INT32),
            FieldSchema("category_code", DataType.VARCHAR, max_length=64),
            FieldSchema("visibility_type", DataType.VARCHAR, max_length=16),
            FieldSchema("content_hash", DataType.VARCHAR, max_length=64),
            FieldSchema("updated_at", DataType.INT64),
        ]
        schema = CollectionSchema(fields, description="agent-gongdan knowledge chunks")
        self.client.create_collection(
            collection_name=self.collection_name,
            schema=schema,
            index_params=self._index_params(),
        )
        self.client.load_collection(self.collection_name)

    def upsert(self, chunks, vectors) -> None:
        now = int(time.time() * 1000)
        rows = [
            {
                "vector_id": chunk.vector_id,
                "embedding": list(vector),
                "doc_id": chunk.doc_id,
                "doc_version": chunk.doc_version,
                "chunk_no": chunk.chunk_no,
                "category_code": chunk.category_code,
                "visibility_type": chunk.visibility_type,
                "content_hash": chunk.content_hash,
                "updated_at": now,
            }
            for chunk, vector in zip(chunks, vectors)
        ]
        self.client.upsert(collection_name=self.collection_name, data=rows)
        self.client.flush(self.collection_name)

    def delete(self, vector_ids) -> int:
        ids = [str(vector_id) for vector_id in vector_ids if vector_id]
        if not ids:
            return 0
        self.client.delete(collection_name=self.collection_name, ids=ids)
        self.client.flush(self.collection_name)
        return len(ids)

    def search(self, vector, top_k: int, category_code: str | None = None):
        kwargs = {
            "collection_name": self.collection_name,
            "data": [list(vector)],
            "anns_field": "embedding",
            "limit": top_k,
            "output_fields": [
                "doc_id", "doc_version", "chunk_no", "vector_id",
                "category_code", "visibility_type",
            ],
            "search_params": {"metric_type": self.metric_type, "params": {}},
        }
        if category_code:
            safe = category_code.replace('"', "")
            kwargs["filter"] = f'category_code == "{safe}"'

        results = self.client.search(**kwargs)
        hits = []
        for group in results:
            for hit in group:
                entity = hit.get("entity") if isinstance(hit, dict) else None
                entity = entity or hit
                hits.append(RetrievalHit(
                    doc_id=int(entity.get("doc_id")),
                    doc_version=int(entity.get("doc_version")),
                    chunk_no=int(entity.get("chunk_no")),
                    vector_id=str(entity.get("vector_id")),
                    score=float(hit.get("distance", hit.get("score", 0.0))),
                    category_code=str(entity.get("category_code")),
                ))
        return hits
