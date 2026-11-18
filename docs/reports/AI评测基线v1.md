# AI 评测基线 v1

> 日期：2026-11-18
> 类型：非门禁基线，用于观察趋势，不作为发布硬门禁。
> 样本：12 条

## 结果

| 用例 | 类型 | 结果 | 详情 | 延迟 |
|---|---|---|---|---|
| kb-1 | knowledge | PASS | citations=4 keyword=True | 4575ms |
| kb-2 | knowledge | PASS | citations=5 keyword=True | 3220ms |
| kb-3 | knowledge | PASS | citations=4 keyword=True | 3020ms |
| kb-4 | knowledge | PASS | citations=5 keyword=True | 3560ms |
| kb-5 | knowledge | PASS | citations=4 keyword=True | 4238ms |
| triage-1 | triage | PASS | intent=FAULT_REPORT action=CREATE_DRAFT | 6517ms |
| triage-2 | triage | PASS | intent=FAULT_REPORT action=CREATE_DRAFT | 6496ms |
| triage-3 | triage | PASS | intent=QUERY action=ANSWER | 3419ms |
| tool-1 | tool | PASS | tools=['ticket_query'] | 3265ms |
| tool-2 | tool | PASS | tools=['ticket_similar'] | 4222ms |
| tool-3 | tool | PASS | tools=['knowledge_search'] | 4095ms |
| export-1 | export | PASS | tools=['ticket_export'] export_card=True | 3806ms |

## 汇总

- 通过：12/12
- 通过率：100.0%

## 说明

- 知识用例以“有引用”为主，关键词命中为辅；
- 分诊用例以 intent/action 为主；
- 工具用例以工具名出现为主，不要求参数完全一致；
- 导出用例检查 `ticket_export` 与 `export_card`；
- v1 只建立基线，不设置自动失败阈值。
