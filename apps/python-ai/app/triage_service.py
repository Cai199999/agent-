"""分诊应用服务：一次同步 JSON 调用，产出意图、实体与工单草稿。

草稿必须由用户在 Java 侧确认后才会真正建单（人在回路）。
"""

import asyncio
import json
import time
from collections.abc import Sequence

TRIAGE_SYSTEM_PROMPT = """你是 IT 运维工单分诊器。请根据用户最新消息与最近对话，只输出一个 JSON 对象，不要输出任何解释。

JSON 结构：
{
  "intent_code": "FAULT_REPORT | QUERY | OPERATION | COMPLAINT | OTHER",
  "confidence": 0.0,
  "entities": {"location": "", "equipment": "", "system": "", "symptom": "", "error_code": "", "urgency": ""},
  "action": "ANSWER | CREATE_DRAFT | CLARIFY | HANDOFF",
  "draft": {
    "title": "",
    "description": "",
    "ticket_type_code": "ELEVATOR | HVAC | PUMP | FIRE | ACCESS | NETWORK | ELECTRICAL | GENERAL",
    "priority_code": "LOW | NORMAL | HIGH | URGENT",
    "location_text": ""
  }
}

判定规则：
1. 明确报修/故障，且具备可执行信息 → action=CREATE_DRAFT，并填写 draft。
2. 纯咨询、查流程、问知识 → action=ANSWER，draft 为 null。
3. 缺少位置、设备或现象等关键信息 → action=CLARIFY，confidence 降低。
4. 涉及困人、漏电、火情、燃气、人员受伤 → action=CREATE_DRAFT，priority_code=URGENT。
5. 投诉、要求人工客服或无法处理 → action=HANDOFF，draft 为 null。
6. 不得编造用户未提供的位置、设备或联系方式；title 不超过 40 个汉字。
7. confidence 取 0 到 1，表示你对 intent 与 action 的综合把握。"""

VALID_INTENTS = {"FAULT_REPORT", "QUERY", "OPERATION", "COMPLAINT", "OTHER"}
VALID_ACTIONS = {"ANSWER", "CREATE_DRAFT", "CLARIFY", "HANDOFF"}
VALID_TICKET_TYPES = {"ELEVATOR", "HVAC", "PUMP", "FIRE", "ACCESS", "NETWORK", "ELECTRICAL", "GENERAL"}
VALID_PRIORITIES = {"LOW", "NORMAL", "HIGH", "URGENT"}


class TriageService:
    def __init__(self, llm_client, confidence_threshold: float = 0.75,
                 timeout_seconds: float = 10.0, prompt_version: str = "v1",
                 max_history: int = 6):
        self.llm_client = llm_client
        self.confidence_threshold = confidence_threshold
        self.timeout_seconds = timeout_seconds
        self.prompt_version = prompt_version
        self.max_history = max_history

    async def triage(self, messages: Sequence[dict[str, str]]) -> dict:
        started = time.perf_counter()
        prompt = self._build_messages(messages)
        raw = await asyncio.wait_for(
            self.llm_client.complete_json(prompt),
            timeout=self.timeout_seconds,
        )
        data = json.loads(raw or "{}")
        result = self._normalize(data)
        result["model"] = getattr(self.llm_client, "model", "")
        result["prompt_version"] = self.prompt_version
        result["latency_ms"] = int((time.perf_counter() - started) * 1000)
        return result

    def _build_messages(self, messages: Sequence[dict[str, str]]) -> list[dict[str, str]]:
        lines = []
        for message in messages[-self.max_history:]:
            role = "用户" if message.get("role") == "user" else "助手"
            lines.append(f"{role}：{message.get('content', '')}")
        return [
            {"role": "system", "content": TRIAGE_SYSTEM_PROMPT},
            {"role": "user", "content": "对话如下：\n" + "\n".join(lines)},
        ]

    def _normalize(self, data: dict) -> dict:
        intent = self._upper(data.get("intent_code"), "OTHER", VALID_INTENTS)
        action = self._upper(data.get("action"), "ANSWER", VALID_ACTIONS)
        confidence = self._clamp(data.get("confidence"))
        entities = data.get("entities") if isinstance(data.get("entities"), dict) else {}

        draft = data.get("draft")
        normalized_draft = None
        if action == "CREATE_DRAFT":
            normalized_draft = self._normalize_draft(draft)
            if not normalized_draft["title"]:
                action = "CLARIFY"

        return {
            "intent_code": intent,
            "confidence": confidence,
            "entities": entities,
            "action": action,
            "low_confidence": confidence < self.confidence_threshold,
            "draft": normalized_draft,
        }

    @staticmethod
    def _normalize_draft(draft) -> dict:
        if not isinstance(draft, dict):
            return {"title": "", "description": "", "ticket_type_code": "GENERAL",
                    "priority_code": "NORMAL", "location_text": ""}
        title = str(draft.get("title") or "").strip()[:200]
        description = str(draft.get("description") or "").strip()[:2000]
        ticket_type = str(draft.get("ticket_type_code") or "GENERAL").strip().upper()
        if ticket_type not in VALID_TICKET_TYPES:
            ticket_type = "GENERAL"
        priority = str(draft.get("priority_code") or "NORMAL").strip().upper()
        if priority not in VALID_PRIORITIES:
            priority = "NORMAL"
        location = str(draft.get("location_text") or "").strip()[:255]
        return {
            "title": title,
            "description": description,
            "ticket_type_code": ticket_type,
            "priority_code": priority,
            "location_text": location,
        }

    @staticmethod
    def _upper(value, default: str, allowed: set[str]) -> str:
        text = str(value or "").strip().upper()
        return text if text in allowed else default

    @staticmethod
    def _clamp(value) -> float:
        try:
            number = float(value)
        except (TypeError, ValueError):
            return 0.0
        return round(min(max(number, 0.0), 1.0), 4)
