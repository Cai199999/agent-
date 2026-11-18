"""Agent 工具执行器。

- 只读工具：通过 Java 内部 API 执行，Python 不接触数据库。
- 写工具：只生成待确认草稿，由 Java 在用户确认后落库。
"""

import json
from datetime import datetime
from typing import Any

from adapters.gateways.java_internal_client import JavaInternalClient, JavaInternalError
from app.tool_registry import ToolRegistryImpl
from app.triage_service import VALID_PRIORITIES, VALID_TICKET_TYPES
from domain.tool import ToolCall, ToolResult


class ToolExecutorImpl:
    def __init__(self, registry: ToolRegistryImpl, gateway: JavaInternalClient):
        self.registry = registry
        self.gateway = gateway

    async def execute(self, call: ToolCall, actor: dict,
                      trace_id: str | None = None) -> ToolResult:
        spec = self.registry.get(call.name)
        if spec is None:
            return ToolResult(call.tool_call_id, False, error_code="tool_not_found",
                              message=f"未知工具 {call.name}")
        arguments = self._parse_arguments(call.arguments)
        if arguments is None:
            return ToolResult(call.tool_call_id, False, error_code="tool_invalid_arguments",
                              message="工具参数不是合法 JSON")
        try:
            if call.name == "ticket_query":
                return await self._ticket_query(call.tool_call_id, arguments, actor, trace_id)
            if call.name == "ticket_export":
                return self._ticket_export(call.tool_call_id, arguments)
            if call.name == "ticket_detail":
                return await self._ticket_detail(call.tool_call_id, arguments, actor, trace_id)
            if call.name == "ticket_similar":
                return await self._ticket_similar(call.tool_call_id, arguments, actor, trace_id)
            if call.name == "knowledge_search":
                return await self._knowledge_search(call.tool_call_id, arguments, actor, trace_id)
            if call.name == "ticket_create":
                return self._ticket_create(call.tool_call_id, arguments)
            return ToolResult(call.tool_call_id, False, error_code="tool_not_implemented",
                              message=f"工具 {call.name} 未实现")
        except JavaInternalError as exc:
            return ToolResult(call.tool_call_id, False, error_code="tool_failed",
                              message=str(exc))
        except Exception as exc:  # noqa: BLE001
            return ToolResult(call.tool_call_id, False, error_code="tool_failed",
                              message=f"工具执行失败：{exc}")

    async def _ticket_query(self, call_id: str, args: dict, actor: dict,
                            trace_id: str | None) -> ToolResult:
        limit = self._int(args.get("limit"), 5, 1, 10)
        status = self._text(args.get("status")) or None
        keyword = self._text(args.get("keyword")) or None
        data = await self.gateway.list_tickets(actor, status, keyword, limit, trace_id)
        tickets = data.get("items") or []
        compact = [
            {
                "id": str(item.get("id")),
                "ticket_no": item.get("ticketNo"),
                "title": item.get("title"),
                "status_code": item.get("statusCode"),
                "priority_code": item.get("priorityCode"),
                "created_at": item.get("createdAt"),
            }
            for item in tickets
        ]
        return ToolResult(
            call_id, True,
            data={"count": len(compact), "tickets": compact},
            message=f"找到 {len(compact)} 条工单",
        )

    async def _ticket_similar(self, call_id: str, args: dict, actor: dict,
                              trace_id: str | None) -> ToolResult:
        query = self._text(args.get("query"))
        if not query:
            return ToolResult(call_id, False, error_code="tool_invalid_arguments",
                              message="缺少 query")
        top_k = self._int(args.get("top_k"), 5, 1, 10)
        data = await self.gateway.similar_tickets(actor, query, top_k, trace_id)
        tickets = data.get("items") or []
        compact = [
            {
                "ticket_id": str(item.get("ticketId")),
                "ticket_no": item.get("ticketNo"),
                "title": item.get("title"),
                "status_code": item.get("statusCode"),
                "ticket_type_code": item.get("ticketTypeCode"),
                "score": round(float(item.get("score") or 0.0), 4),
            }
            for item in tickets
        ]
        return ToolResult(
            call_id, True,
            data={"count": len(compact), "tickets": compact},
            message=f"找到 {len(compact)} 条相似工单",
        )

    async def _knowledge_search(self, call_id: str, args: dict, actor: dict,
                                trace_id: str | None) -> ToolResult:
        query = self._text(args.get("query"))
        if not query:
            return ToolResult(call_id, False, error_code="tool_invalid_arguments",
                              message="缺少 query")
        top_k = self._int(args.get("top_k"), 5, 1, 10)
        data = await self.gateway.search_knowledge(actor, query, top_k, trace_id)
        hits = data.get("items") or []
        compact = [
            {
                "doc_id": str(item.get("docId")),
                "doc_no": item.get("docNo"),
                "title": item.get("title"),
                "chunk_no": item.get("chunkNo"),
                "score": round(float(item.get("score") or 0.0), 4),
                "content": str(item.get("content") or "")[:300],
            }
            for item in hits
        ]
        return ToolResult(
            call_id, True,
            data={"count": len(compact), "hits": compact},
            message=f"命中 {len(compact)} 条知识",
        )

    def _ticket_export(self, call_id: str, args: dict) -> ToolResult:
        """只生成导出草稿，不生成文件；由用户确认后由 Java 执行。"""
        start_raw = self._text(args.get("start_time"))
        end_raw = self._text(args.get("end_time"))
        try:
            start = self._parse_iso(start_raw)
            end = self._parse_iso(end_raw)
        except ValueError:
            return ToolResult(call_id, False, error_code="tool_invalid_arguments",
                              message="时间范围必须是带时区的 ISO 8601，例如 2026-11-10T00:00:00+08:00")
        if start is None or end is None:
            return ToolResult(call_id, False, error_code="tool_invalid_arguments",
                              message="导出必须提供 start_time 和 end_time")
        if end <= start:
            return ToolResult(call_id, False, error_code="tool_invalid_arguments",
                              message="end_time 必须晚于 start_time")
        status = self._text(args.get("status")).upper() or None
        ticket_type = self._text(args.get("ticket_type_code")).upper() or None
        priority = self._text(args.get("priority_code")).upper() or None
        if ticket_type and ticket_type not in VALID_TICKET_TYPES:
            return ToolResult(call_id, False, error_code="tool_invalid_arguments",
                              message="不支持的工单类型")
        if priority and priority not in VALID_PRIORITIES:
            return ToolResult(call_id, False, error_code="tool_invalid_arguments",
                              message="不支持的优先级")
        filters = {
            "start_time": start.isoformat(),
            "end_time": end.isoformat(),
            "status": status,
            "ticket_type_code": ticket_type,
            "priority_code": priority,
            "keyword": self._text(args.get("keyword")) or None,
            "location": self._text(args.get("location")) or None,
        }
        summary = self._export_summary(filters)
        return ToolResult(
            call_id, True,
            data={"state": "CONFIRM_REQUIRED", "filters": filters, "summary": summary},
            message=summary,
        )

    @staticmethod
    def _parse_iso(value: str) -> datetime | None:
        text = (value or "").strip()
        if not text:
            return None
        if text.endswith("Z"):
            text = text[:-1] + "+00:00"
        parsed = datetime.fromisoformat(text)
        if parsed.tzinfo is None:
            raise ValueError("timezone required")
        return parsed

    @staticmethod
    def _export_summary(filters: dict) -> str:
        def short(value: str | None) -> str:
            if not value:
                return ""
            try:
                return datetime.fromisoformat(value.replace("Z", "+00:00")).strftime("%Y-%m-%d %H:%M")
            except ValueError:
                return value

        parts = [f"将导出 {short(filters.get('start_time'))} 至 {short(filters.get('end_time'))} 的工单"]
        labels = {
            "ELEVATOR": "电梯故障",
            "HVAC": "空调故障",
            "PUMP": "水泵故障",
            "FIRE": "消防故障",
            "ACCESS": "门禁故障",
            "NETWORK": "网络故障",
            "ELECTRICAL": "电气故障",
            "GENERAL": "通用",
        }
        if filters.get("ticket_type_code"):
            parts.append(f"类型：{labels.get(filters['ticket_type_code'], filters['ticket_type_code'])}")
        if filters.get("status"):
            parts.append(f"状态：{filters['status']}")
        if filters.get("priority_code"):
            parts.append(f"优先级：{filters['priority_code']}")
        if filters.get("location"):
            parts.append(f"位置：{filters['location']}")
        if filters.get("keyword"):
            parts.append(f"关键词：{filters['keyword']}")
        return "，".join(parts) + "。确认后生成 Excel。"

    async def _ticket_detail(self, call_id: str, args: dict, actor: dict,
                             trace_id: str | None) -> ToolResult:
        ticket_id = self._text(args.get("ticket_id"))
        if not ticket_id:
            return ToolResult(call_id, False, error_code="tool_invalid_arguments",
                              message="缺少 ticket_id")
        data = await self.gateway.get_ticket(actor, ticket_id, trace_id)
        ticket = data.get("ticket") or {}
        return ToolResult(
            call_id, True,
            data={
                "ticket": {
                    "id": str(ticket.get("id")),
                    "ticket_no": ticket.get("ticketNo"),
                    "title": ticket.get("title"),
                    "status_code": ticket.get("statusCode"),
                    "priority_code": ticket.get("priorityCode"),
                    "ticket_type_code": ticket.get("ticketTypeCode"),
                    "location_text": ticket.get("locationText"),
                    "assignee_id": ticket.get("assigneeId"),
                    "created_at": ticket.get("createdAt"),
                },
                "description": data.get("description"),
                "recent_events": [
                    {"event_type": event.get("eventType"), "created_at": event.get("createdAt")}
                    for event in (data.get("events") or [])[-5:]
                ],
            },
            message="工单详情已获取",
        )

    @staticmethod
    def _ticket_create(call_id: str, args: dict) -> ToolResult:
        title = str(args.get("title") or "").strip()[:200]
        if not title:
            return ToolResult(call_id, False, error_code="tool_invalid_arguments",
                              message="工单标题不能为空")
        ticket_type = str(args.get("ticket_type_code") or "GENERAL").strip().upper()
        if ticket_type not in VALID_TICKET_TYPES:
            ticket_type = "GENERAL"
        priority = str(args.get("priority_code") or "NORMAL").strip().upper()
        if priority not in VALID_PRIORITIES:
            priority = "NORMAL"
        draft = {
            "title": title,
            "description": str(args.get("description") or "").strip()[:2000],
            "ticket_type_code": ticket_type,
            "priority_code": priority,
            "location_text": str(args.get("location_text") or "").strip()[:255],
        }
        return ToolResult(
            call_id, True,
            data={"state": "CONFIRM_REQUIRED", "draft": draft},
            message="工单草稿已生成，等待用户确认",
        )

    @staticmethod
    def summarize(name: str, args: dict) -> str:
        if name == "ticket_query":
            return f"状态={args.get('status') or '全部'}，关键词={args.get('keyword') or '无'}，" \
                   f"条数={args.get('limit') or 5}"
        if name == "ticket_export":
            return f"导出范围={args.get('start_time') or '?'} ~ {args.get('end_time') or '?'}"
        if name == "ticket_detail":
            return f"工单={args.get('ticket_id') or '未指定'}"
        if name == "ticket_similar":
            return f"描述={str(args.get('query') or '')[:40]}"
        if name == "knowledge_search":
            return f"检索={str(args.get('query') or '')[:40]}"
        if name == "ticket_create":
            return f"标题={str(args.get('title') or '')[:40]}"
        return ""

    @staticmethod
    def _parse_arguments(raw: Any) -> dict | None:
        if isinstance(raw, dict):
            return raw
        if not raw:
            return {}
        try:
            parsed = json.loads(raw)
        except (TypeError, ValueError):
            return None
        return parsed if isinstance(parsed, dict) else None

    @staticmethod
    def _text(value: Any) -> str:
        return str(value).strip() if value is not None else ""

    @staticmethod
    def _int(value: Any, default: int, minimum: int, maximum: int) -> int:
        try:
            number = int(value)
        except (TypeError, ValueError):
            return default
        return min(max(number, minimum), maximum)
