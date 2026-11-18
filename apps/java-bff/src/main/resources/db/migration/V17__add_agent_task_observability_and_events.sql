-- V17: 子 Agent 任务观测字段与事件
ALTER TABLE agent_task
    ADD COLUMN input_tokens INT NULL COMMENT '输入 token（LLM 任务）' AFTER latency_ms,
    ADD COLUMN output_tokens INT NULL COMMENT '输出 token（LLM 任务）' AFTER input_tokens,
    ADD COLUMN cost_micros BIGINT NULL COMMENT '成本（微单位，可选）' AFTER output_tokens,
    ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'SYNC' COMMENT 'SYNC/ASYNC' AFTER cost_micros;

CREATE TABLE agent_task_event (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    task_id VARCHAR(64) NOT NULL COMMENT '任务 UUID',
    status VARCHAR(32) NOT NULL COMMENT '任务状态',
    message VARCHAR(500) NULL COMMENT '说明',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_agent_task_event_task (task_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='子 Agent 任务事件';
