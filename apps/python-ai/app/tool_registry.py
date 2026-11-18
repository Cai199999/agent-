"""Agent 工具注册表。

工具契约见 domain/tool.py；本层只登记元数据，执行在 app/tool_executor.py。
"""

from domain.tool import ToolRiskLevel, ToolSpec

TICKET_QUERY = ToolSpec(
    name="ticket_query",
    description="查询当前用户可见的工单列表。用户询问工单进度、待办、最近报修时使用。",
    input_schema={
        "type": "object",
        "properties": {
            "status": {
                "type": "string",
                "description": "工单状态过滤，如 PENDING/PROCESSING/PENDING_VERIFY/CLOSED；不传则全部",
            },
            "keyword": {"type": "string", "description": "标题关键词，可选"},
            "limit": {"type": "integer", "description": "返回条数，1-10，默认 5"},
        },
    },
    output_schema={"type": "object", "properties": {"tickets": {"type": "array"}}},
    permission="ticket:read",
    risk_level=ToolRiskLevel.LOW,
    timeout_seconds=3,
)

TICKET_EXPORT = ToolSpec(
    name="ticket_export",
    description=(
        "准备导出工单 Excel。用户要求导出工单/生成报表时必须使用；"
        "必须提供明确的 start_time 和 end_time（ISO 8601，含时区）。"
        "该工具只生成待确认草稿，不会立即导出；必须由用户确认后才生成文件。"
    ),
    input_schema={
        "type": "object",
        "properties": {
            "start_time": {
                "type": "string",
                "description": "开始时间，ISO 8601 带时区，例如 2026-11-10T00:00:00+08:00",
            },
            "end_time": {
                "type": "string",
                "description": "结束时间，ISO 8601 带时区，例如 2026-11-17T23:59:59+08:00",
            },
            "status": {
                "type": "string",
                "description": "工单状态，如 PENDING/PROCESSING/CLOSED；可选",
            },
            "ticket_type_code": {
                "type": "string",
                "description": "工单类型，如 ELEVATOR/HVAC/PUMP/FIRE/ACCESS/NETWORK/ELECTRICAL/GENERAL；可选",
            },
            "priority_code": {
                "type": "string",
                "description": "优先级 LOW/NORMAL/HIGH/URGENT；可选",
            },
            "keyword": {"type": "string", "description": "标题/工单号关键词；可选"},
            "location": {"type": "string", "description": "位置关键词，如 A栋；可选"},
        },
        "required": ["start_time", "end_time"],
    },
    output_schema={
        "type": "object",
        "properties": {
            "state": {"type": "string"},
            "filters": {"type": "object"},
            "summary": {"type": "string"},
        },
    },
    permission="ticket:read",
    risk_level=ToolRiskLevel.MEDIUM,
    requires_confirm=True,
    idempotent=True,
    timeout_seconds=5,
)

TICKET_DETAIL = ToolSpec(
    name="ticket_detail",
    description="按工单 ID 查询工单详情。通常先用 ticket_query 获取 ID。",
    input_schema={
        "type": "object",
        "properties": {"ticket_id": {"type": "string", "description": "工单 ID（雪花 ID 字符串）"}},
        "required": ["ticket_id"],
    },
    output_schema={"type": "object", "properties": {"ticket": {"type": "object"}}},
    permission="ticket:read",
    risk_level=ToolRiskLevel.LOW,
    timeout_seconds=3,
)

TICKET_CREATE = ToolSpec(
    name="ticket_create",
    description="生成报修工单草稿。必须由用户在界面上确认后才会真正建单；信息不足时不要调用。",
    input_schema={
        "type": "object",
        "properties": {
            "title": {"type": "string", "description": "工单标题，不超过 40 个汉字"},
            "description": {"type": "string", "description": "故障现象与已尝试的处理"},
            "ticket_type_code": {
                "type": "string",
                "description": "ELEVATOR/HVAC/PUMP/FIRE/ACCESS/NETWORK/ELECTRICAL/GENERAL",
            },
            "priority_code": {"type": "string", "description": "LOW/NORMAL/HIGH/URGENT"},
            "location_text": {"type": "string", "description": "位置描述，如 A栋2号梯"},
        },
        "required": ["title"],
    },
    output_schema={"type": "object", "properties": {"state": {"type": "string"}, "draft": {"type": "object"}}},
    permission="ticket:create",
    risk_level=ToolRiskLevel.HIGH,
    requires_confirm=True,
    idempotent=False,
    timeout_seconds=5,
)

TICKET_SIMILAR = ToolSpec(
    name="ticket_similar",
    description="按故障描述检索历史相似工单。用户问“以前有没有类似问题/历史上怎么处理”时使用。",
    input_schema={
        "type": "object",
        "properties": {
            "query": {"type": "string", "description": "故障现象、设备与位置描述"},
            "top_k": {"type": "integer", "description": "返回条数，1-10，默认 5"},
        },
        "required": ["query"],
    },
    output_schema={"type": "object", "properties": {"tickets": {"type": "array"}}},
    permission="ticket:read",
    risk_level=ToolRiskLevel.LOW,
    timeout_seconds=5,
)

KNOWLEDGE_SEARCH = ToolSpec(
    name="knowledge_search",
    description="检索知识库文档片段，返回标题、命中内容与相似度。用户问操作流程、规范或处置方案时使用。",
    input_schema={
        "type": "object",
        "properties": {
            "query": {"type": "string", "description": "检索关键词或问题"},
            "top_k": {"type": "integer", "description": "返回条数，1-10，默认 5"},
        },
        "required": ["query"],
    },
    output_schema={"type": "object", "properties": {"hits": {"type": "array"}}},
    permission="knowledge:read",
    risk_level=ToolRiskLevel.LOW,
    timeout_seconds=5,
)

DEFAULT_SPECS = [TICKET_QUERY, TICKET_EXPORT, TICKET_DETAIL, TICKET_SIMILAR,
                 KNOWLEDGE_SEARCH, TICKET_CREATE]


class ToolRegistryImpl:
    def __init__(self, specs=DEFAULT_SPECS):
        self._specs = {spec.name: spec for spec in specs}

    def get(self, name: str) -> ToolSpec | None:
        return self._specs.get(name)

    def list_tools(self) -> list[ToolSpec]:
        return list(self._specs.values())

    def openai_tools(self) -> list[dict]:
        return [
            {
                "type": "function",
                "function": {
                    "name": spec.name,
                    "description": spec.description,
                    "parameters": spec.input_schema,
                },
            }
            for spec in self._specs.values()
        ]
