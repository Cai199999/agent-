"""工具契约领域模型。

仅定义结构，不包含任何工具实现；工具注册与执行在后续阶段接入。
"""

from dataclasses import dataclass
from enum import Enum
from typing import Any


class ToolRiskLevel(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str
    input_schema: dict[str, Any]
    output_schema: dict[str, Any]
    permission: str
    risk_level: ToolRiskLevel = ToolRiskLevel.LOW
    requires_confirm: bool = False
    idempotent: bool = False
    timeout_seconds: int = 5
    max_retries: int = 1
    version: str = "v1"


@dataclass(frozen=True)
class ToolCall:
    tool_call_id: str
    name: str
    arguments: dict[str, Any]


@dataclass(frozen=True)
class ToolResult:
    tool_call_id: str
    success: bool
    data: dict[str, Any] | None = None
    error_code: str | None = None
    message: str | None = None
