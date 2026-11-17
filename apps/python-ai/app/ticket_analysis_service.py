"""工单 AI 初步分析：基于工单信息与知识片段给出建议。

只输出结构化 JSON；引用编号与 Java 侧传入的知识片段一一对应。
"""

import asyncio
import json
import time
from collections.abc import Sequence

ANALYSIS_SYSTEM_PROMPT = """你是物业与 IT 运维工单分析助手。请根据工单信息和知识片段，只输出一个 JSON 对象，不要输出解释。

JSON 结构：
{
  "summary": "一句话初步判断与建议",
  "causes": ["可能原因1", "可能原因2"],
  "actions": ["建议动作1", "建议动作2"],
  "risk_level": "LOW | MEDIUM | HIGH"
}

规则：
1. 优先依据【知识片段】；引用时在句末标注编号，如 [1]。
2. 不得编造未提供的设备参数、位置、电话或法规条款。
3. 涉及困人、漏电、火情、燃气、人员受伤，risk_level=HIGH，并先给出止损与报警建议。
4. actions 要可执行、按顺序排列；causes 最多 4 条，actions 最多 6 条。
5. summary 不超过 120 个汉字。"""

VALID_RISKS = {"LOW", "MEDIUM", "HIGH"}


class TicketAnalysisService:
    def __init__(self, llm_client, prompt_version: str = "v1", timeout_seconds: float = 20.0):
        self.llm_client = llm_client
        self.prompt_version = prompt_version
        self.timeout_seconds = timeout_seconds

    async def analyze(self, ticket: dict, chunks: Sequence[dict]) -> dict:
        started = time.perf_counter()
        messages = self._build_messages(ticket, chunks)
        raw = await asyncio.wait_for(
            self.llm_client.complete_json(messages),
            timeout=self.timeout_seconds,
        )
        data = json.loads(raw or "{}")
        summary = str(data.get("summary") or "").strip()[:500]
        causes = self._string_list(data.get("causes"), 4)
        actions = self._string_list(data.get("actions"), 6)
        risk = str(data.get("risk_level") or "MEDIUM").upper()
        if risk not in VALID_RISKS:
            risk = "MEDIUM"
        return {
            "summary": summary,
            "causes": causes,
            "actions": actions,
            "risk_level": risk,
            "model": getattr(self.llm_client, "model", ""),
            "prompt_version": self.prompt_version,
            "latency_ms": int((time.perf_counter() - started) * 1000),
        }

    def _build_messages(self, ticket: dict, chunks: Sequence[dict]) -> list[dict[str, str]]:
        lines = [
            f"工单号：{ticket.get('ticket_no') or '-'}",
            f"标题：{ticket.get('title') or '-'}",
            f"描述：{ticket.get('description') or '-'}",
            f"类型：{ticket.get('ticket_type_code') or '-'}",
            f"优先级：{ticket.get('priority_code') or '-'}",
            f"状态：{ticket.get('status_code') or '-'}",
            f"位置：{ticket.get('location_text') or '-'}",
        ]
        if chunks:
            lines.append("")
            lines.append("【知识片段】")
            for chunk in chunks:
                lines.append(f"[{chunk.get('rank')}] 标题：{chunk.get('title') or '未命名知识'}")
                lines.append(str(chunk.get("content") or "").strip())
                lines.append("")
        else:
            lines.append("")
            lines.append("【知识片段】无。请基于通用运维常识谨慎分析，并提示补充信息。")
        return [
            {"role": "system", "content": ANALYSIS_SYSTEM_PROMPT},
            {"role": "user", "content": "\n".join(lines)},
        ]

    @staticmethod
    def _string_list(value, limit: int) -> list[str]:
        if not isinstance(value, list):
            return []
        result = []
        for item in value:
            text = str(item).strip()
            if text:
                result.append(text[:300])
            if len(result) >= limit:
                break
        return result
