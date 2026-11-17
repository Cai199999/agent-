"""进程内共享的 Milvus Lite 客户端。

Milvus Lite 以本地文件运行，多个 MilvusClient 会争用同一文件锁；
知识库与工单向量必须复用同一个客户端实例。
"""

from functools import lru_cache

from pymilvus import MilvusClient

from config import settings


@lru_cache(maxsize=1)
def get_milvus_client() -> MilvusClient:
    settings.milvus_path.parent.mkdir(parents=True, exist_ok=True)
    return MilvusClient(uri=str(settings.milvus_path))
