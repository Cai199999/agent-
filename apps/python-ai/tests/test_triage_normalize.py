"""分诊归一化测试：位置缺失时不得生成草稿。"""

from __future__ import annotations

import unittest

from app.triage_service import TriageService


class TriageNormalizeTest(unittest.TestCase):

    def setUp(self) -> None:
        self.service = TriageService(llm_client=None)

    def test_missing_location_downgrades_to_clarify(self):
        result = self.service._normalize({
            "intent_code": "FAULT_REPORT",
            "confidence": 0.95,
            "action": "CREATE_DRAFT",
            "entities": {"equipment": "电梯", "symptom": "困人"},
            "draft": {
                "title": "电梯困人",
                "description": "有人被困",
                "ticket_type_code": "ELEVATOR",
                "priority_code": "URGENT",
                "location_text": "",
            },
        })

        self.assertEqual(result["action"], "CLARIFY")
        self.assertIsNone(result["draft"])

    def test_location_present_keeps_draft(self):
        result = self.service._normalize({
            "intent_code": "FAULT_REPORT",
            "confidence": 0.95,
            "action": "CREATE_DRAFT",
            "draft": {
                "title": "A栋电梯困人",
                "description": "有人被困",
                "ticket_type_code": "ELEVATOR",
                "priority_code": "URGENT",
                "location_text": "A栋",
            },
        })

        self.assertEqual(result["action"], "CREATE_DRAFT")
        self.assertEqual(result["draft"]["location_text"], "A栋")


if __name__ == "__main__":
    unittest.main()
