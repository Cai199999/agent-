-- V18: 子 Agent 委托身份审计
ALTER TABLE agent_task
    ADD COLUMN delegation_id VARCHAR(64) NULL COMMENT '委托令牌 ID（未使用委托时为 NULL）' AFTER execution_mode,
    ADD KEY idx_agent_task_delegation (delegation_id);
