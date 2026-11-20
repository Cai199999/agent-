"""Agent 工具注册表。

工具契约见 domain/tool.py；本层只登记元数据，执行在 app/tool_executor.py。
"""

from domain.tool import ToolRiskLevel, ToolSpec

TICKET_QUERY = ToolSpec(
    name="ticket_query",
    description=(
        "查询当前用户可见的工单列表。用户询问工单进度、待办、最近报修、"
        "某人手里的单、超时/未完结单、某时间段的紧急单时使用。"
        "支持状态、优先级、处理人、时间范围与未完结筛选。"
    ),
    input_schema={
        "type": "object",
        "properties": {
            "status": {
                "type": "string",
                "description": "工单状态过滤，如 PENDING/PROCESSING/PENDING_VERIFY/CLOSED；不传则全部",
            },
            "keyword": {"type": "string", "description": "标题/工单号关键词，可选"},
            "priority_code": {
                "type": "string",
                "description": "优先级 LOW/NORMAL/HIGH/URGENT；可选",
            },
            "assignee_id": {
                "type": "string",
                "description": "处理人用户 ID（数字字符串），用于查某人手里的单；可选",
            },
            "start_time": {
                "type": "string",
                "description": "创建时间起，ISO 8601 带时区，例如 2026-11-09T00:00:00+08:00；可选",
            },
            "end_time": {
                "type": "string",
                "description": "创建时间止，ISO 8601 带时区；可选",
            },
            "open_only": {
                "type": "boolean",
                "description": "只看未完结（排除 CLOSED/CANCELLED）的工单；可选",
            },
            "limit": {"type": "integer", "description": "返回条数，1-10，默认 5"},
        },
    },
    output_schema={"type": "object", "properties": {"tickets": {"type": "array"}}},
    permission="ticket:read",
    risk_level=ToolRiskLevel.LOW,
    idempotent=True,
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
    idempotent=True,
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
    idempotent=True,
    timeout_seconds=5,
)

USER_LOOKUP = ToolSpec(
    name="user_lookup",
    description=(
        "按姓名或账号查询用户 ID。用户提到具体人员（如“派给张三”“查张三名下的单”）时，"
        "先调用本工具解析用户 ID，再使用 assignee_id 查询或后续派单。"
    ),
    input_schema={
        "type": "object",
        "properties": {
            "keyword": {"type": "string", "description": "姓名、显示名或账号，例如 张三/engineer1"},
            "limit": {"type": "integer", "description": "返回条数，1-20，默认 10"},
        },
        "required": ["keyword"],
    },
    output_schema={"type": "object", "properties": {"users": {"type": "array"}}},
    permission="ticket:read",
    risk_level=ToolRiskLevel.LOW,
    idempotent=True,
    timeout_seconds=3,
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
    idempotent=True,
    timeout_seconds=5,
)

TICKET_ID_PROP = {
    "ticket_id": {
        "type": "string",
        "description": "工单 ID 或工单号（如 GD20261119000107）",
    }
}


def _write_spec(name: str, description: str, properties: dict, required: list[str],
                permission: str, risk: ToolRiskLevel = ToolRiskLevel.HIGH) -> ToolSpec:
    props = dict(TICKET_ID_PROP)
    props.update(properties)
    return ToolSpec(
        name=name,
        description=(description + " 本工具只生成待确认草稿，必须由用户在界面上确认后才会执行；"
                     "未确认不会修改任何工单数据。"),
        input_schema={
            "type": "object",
            "properties": props,
            "required": ["ticket_id", *required],
        },
        output_schema={
            "type": "object",
            "properties": {"state": {"type": "string"}, "summary": {"type": "string"}},
        },
        permission=permission,
        risk_level=risk,
        requires_confirm=True,
        idempotent=False,
        timeout_seconds=3,
    )


TICKET_ASSIGN = _write_spec(
    "ticket_assign", "给工单派单/改派给指定处理人。处理人需先用 user_lookup 解析出用户 ID。",
    {
        "assignee_id": {"type": "string", "description": "处理人数字用户 ID，例如 10002"},
        "reason": {"type": "string", "description": "派单说明；可选"},
    },
    ["assignee_id"], "ticket:assign",
)

TICKET_ACCEPT = _write_spec(
    "ticket_accept", "接单：把工单从待接单变为处理中。",
    {"reason": {"type": "string", "description": "接单说明；可选"}},
    [], "ticket:handle",
)

TICKET_RESOLVE = _write_spec(
    "ticket_resolve", "提交解决：工单需处于处理中，且已上传现场照片。",
    {
        "service_item_text": {"type": "string", "description": "服务项目/处理内容，必填"},
        "onsite_situation": {"type": "string", "description": "现场情况说明；可选"},
    },
    ["service_item_text"], "ticket:handle",
)

TICKET_VERIFY = _write_spec(
    "ticket_verify", "验证：通过或不通过待验证工单。",
    {
        "approved": {"type": "boolean", "description": "true=通过，false=不通过"},
        "reason": {"type": "string", "description": "验证说明；可选"},
    },
    ["approved"], "ticket:verify",
)

TICKET_CLOSE = _write_spec(
    "ticket_close", "关闭工单。",
    {
        "close_reason_code": {"type": "string", "description": "结单原因编码，如 SOLVED；可选"},
        "reason": {"type": "string", "description": "关闭说明；可选"},
    },
    [], "ticket:close",
)

TICKET_CANCEL = _write_spec(
    "ticket_cancel", "取消工单（终态工单不可取消）。",
    {"cancel_reason": {"type": "string", "description": "取消原因，必填"}},
    ["cancel_reason"], "ticket:close",
)

TICKET_HOLD = _write_spec(
    "ticket_hold", "挂起工单，并顺延 SLA。",
    {"reason": {"type": "string", "description": "挂起原因，必填"}},
    ["reason"], "ticket:handle",
)

TICKET_RESUME = _write_spec(
    "ticket_resume", "恢复已挂起的工单。",
    {"reason": {"type": "string", "description": "恢复说明；可选"}},
    [], "ticket:handle",
)

TICKET_FINISH = _write_spec(
    "ticket_finish", "管理员一键结束工单。",
    {"reason": {"type": "string", "description": "结束原因；可选"}},
    [], "ticket:close",
)

TICKET_COMMENT_ADD = _write_spec(
    "ticket_comment_add", "给工单添加评论。",
    {
        "content": {"type": "string", "description": "评论内容，必填"},
        "visibility": {"type": "string", "description": "PUBLIC 或 INTERNAL，默认 PUBLIC"},
    },
    ["content"], "ticket:read", ToolRiskLevel.MEDIUM,
)

TICKET_MATERIAL_ADD = _write_spec(
    "ticket_material_add", "登记工单使用的材料。",
    {
        "material_name": {"type": "string", "description": "材料名称，必填"},
        "specification": {"type": "string", "description": "规格；可选"},
        "quantity": {"type": "number", "description": "数量；可选"},
        "unit": {"type": "string", "description": "单位；可选"},
        "amount": {"type": "number", "description": "金额；可选"},
        "remark": {"type": "string", "description": "备注；可选"},
    },
    ["material_name"], "ticket:handle", ToolRiskLevel.MEDIUM,
)

TICKET_VISIT_ADD = _write_spec(
    "ticket_visit_add", "登记回访；仅已关闭工单可登记。",
    {
        "visit_method": {"type": "string", "description": "回访方式，如 PHONE/ONSITE"},
        "visited_at": {"type": "string", "description": "回访时间 ISO 8601；可选"},
        "visit_result": {"type": "string", "description": "回访结果"},
        "status": {"type": "string", "description": "状态，默认 DONE"},
        "remark": {"type": "string", "description": "备注；可选"},
    },
    [], "ticket:close", ToolRiskLevel.MEDIUM,
)

DEFAULT_SPECS = [
    TICKET_QUERY, TICKET_EXPORT, TICKET_DETAIL, TICKET_SIMILAR,
    KNOWLEDGE_SEARCH, USER_LOOKUP, TICKET_CREATE,
    TICKET_ASSIGN, TICKET_ACCEPT, TICKET_RESOLVE, TICKET_VERIFY,
    TICKET_CLOSE, TICKET_CANCEL, TICKET_HOLD, TICKET_RESUME, TICKET_FINISH,
    TICKET_COMMENT_ADD, TICKET_MATERIAL_ADD, TICKET_VISIT_ADD,
]


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
