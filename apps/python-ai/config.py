"""Python AI 服务配置（pydantic-settings）。

所有密钥、端点与运行参数从项目根 .env 读取；不在代码中硬编码。
"""

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

PROJECT_ROOT = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=PROJECT_ROOT / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    app_env: str = "dev"
    log_level: str = "INFO"
    python_port: int = 8300
    project_root: str = str(PROJECT_ROOT)

    java_base_url: str = "http://127.0.0.1:8200"
    internal_token_p2j_current: str = ""
    internal_token_p2j_next: str = ""
    agent_max_steps: int = 5
    agent_plan_events: bool = True
    agent_tool_retry_max: int = 1
    tool_result_max_chars: int = 4000
    tool_http_timeout_seconds: float = 10.0

    milvus_local_path: str = "./data/milvus/milvus.db"
    milvus_collection_knowledge: str = "agent_gongdan_kb_bge_m3_1024_v1"
    milvus_collection_ticket: str = "agent_gongdan_ticket_bge_m3_1024_v1"
    milvus_metric_type: str = "COSINE"

    embedding_base_url: str = "https://api.siliconflow.cn/v1"
    embedding_model: str = "BAAI/bge-m3"
    embedding_dim: int = 1024
    siliconflow_api_key: str = ""

    llm_base_url: str = "https://api.deepseek.com/v1"
    llm_model: str = "deepseek-chat"
    deepseek_api_key: str = ""
    llm_timeout_seconds: float = 60.0
    llm_temperature: float = 0.2
    llm_max_output_tokens: int = 2048
    chat_max_context_chunks: int = 8
    triage_timeout_seconds: float = 10.0
    triage_confidence_threshold: float = 0.75
    triage_prompt_version: str = "v1"
    triage_max_history: int = 6

    internal_token_j2p_current: str = ""
    internal_token_j2p_next: str = ""

    @property
    def internal_token_p2j(self) -> str:
        return self.internal_token_p2j_current or self.internal_token_p2j_next

    @property
    def milvus_path(self) -> Path:
        path = Path(self.milvus_local_path)
        return path if path.is_absolute() else PROJECT_ROOT / path


settings = Settings()
