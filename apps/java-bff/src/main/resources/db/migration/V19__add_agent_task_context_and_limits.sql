-- V19: 跨 Agent 上下文参数预留（父子任务、关联 ID、引用、预算、截止时间）
-- 只新增列与索引，不修改既有列；不建物理外键。
ALTER TABLE agent_task
    ADD COLUMN parent_task_id VARCHAR(64) NULL COMMENT '上游任务 ID（跨 Agent 调用链）' AFTER delegation_id,
    ADD COLUMN correlation_id VARCHAR(64) NULL COMMENT '调用链关联 ID' AFTER parent_task_id,
    ADD COLUMN context_refs_json JSON NULL COMMENT '上下文引用（只传引用，不传完整聊天历史）' AFTER input_json,
    ADD COLUMN budget_json JSON NULL COMMENT '调用预算（token/耗时等，预留）' AFTER context_refs_json,
    ADD COLUMN deadline_at DATETIME(3) NULL COMMENT '期望完成时间（预留，当前不强制中断）' AFTER budget_json,
    ADD KEY idx_agent_task_parent (parent_task_id),
    ADD KEY idx_agent_task_correlation (correlation_id),
    ADD KEY idx_agent_task_deadline (deadline_at);
