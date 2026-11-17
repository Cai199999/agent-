"""OpenAI 兼容 Embedding 适配器（SiliconFlow bge-m3）。"""

import httpx

from config import settings


class OpenAICompatibleEmbeddingClient:
    def __init__(self) -> None:
        self.base_url = settings.embedding_base_url.rstrip("/")
        self.api_key = settings.siliconflow_api_key
        self.model = settings.embedding_model
        self.dim = settings.embedding_dim

    def embed(self, texts):
        if not texts:
            return []
        response = httpx.post(
            f"{self.base_url}/embeddings",
            headers={"Authorization": f"Bearer {self.api_key}"},
            json={"model": self.model, "input": list(texts)},
            timeout=30.0,
        )
        response.raise_for_status()
        data = response.json().get("data") or []
        data.sort(key=lambda item: item.get("index", 0))
        vectors = [item.get("embedding") or [] for item in data]
        for vector in vectors:
            if len(vector) != self.dim:
                raise ValueError(f"embedding dimension mismatch: {len(vector)} != {self.dim}")
        return vectors
