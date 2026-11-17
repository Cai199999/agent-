"""应用层服务：知识库分块、入库与检索。"""

import hashlib

from domain.knowledge import KnowledgeChunk


class KnowledgeService:
    def __init__(self, embedding_client, vector_store, chunk_size: int = 500, overlap: int = 50):
        self.embedding_client = embedding_client
        self.vector_store = vector_store
        self.chunk_size = chunk_size
        self.overlap = overlap

    def ingest(self, doc_id: int, doc_version: int, category_code: str,
               visibility_type: str, text: str) -> list[KnowledgeChunk]:
        chunks = self._split(doc_id, doc_version, category_code, visibility_type, text)
        if not chunks:
            raise ValueError("text is empty")
        vectors = self.embedding_client.embed([chunk.content for chunk in chunks])
        self.vector_store.upsert(chunks, vectors)
        return chunks

    def search(self, query: str, top_k: int, category_code: str | None = None):
        vector = self.embedding_client.embed([query])[0]
        return self.vector_store.search(vector, top_k, category_code)

    def _split(self, doc_id: int, doc_version: int, category_code: str,
               visibility_type: str, text: str) -> list[KnowledgeChunk]:
        normalized = (text or "").strip()
        if not normalized:
            return []
        chunks: list[KnowledgeChunk] = []
        step = max(1, self.chunk_size - self.overlap)
        chunk_no = 0
        start = 0
        while start < len(normalized):
            piece = normalized[start:start + self.chunk_size].strip()
            if piece:
                digest = hashlib.sha256(piece.encode("utf-8")).hexdigest()
                chunks.append(KnowledgeChunk(
                    doc_id=doc_id,
                    doc_version=doc_version,
                    chunk_no=chunk_no,
                    content=piece,
                    vector_id=f"{doc_id}_{doc_version}_{chunk_no}",
                    content_hash=digest,
                    category_code=category_code,
                    visibility_type=visibility_type,
                ))
                chunk_no += 1
            if start + self.chunk_size >= len(normalized):
                break
            start += step
        return chunks
