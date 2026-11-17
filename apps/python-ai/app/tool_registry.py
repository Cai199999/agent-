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

DEFAULT_SPECS = [TICKET_QUERY, TICKET_DETAIL, TICKET_SIMILAR, KNOWLEDGE_SEARCH, TICKET_CREATE]


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
