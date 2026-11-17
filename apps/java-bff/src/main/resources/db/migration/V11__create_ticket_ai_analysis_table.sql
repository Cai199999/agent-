-- V11: 工单 AI 初步分析结果

CREATE TABLE ticket_ai_analysis (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    ticket_id BIGINT NOT NULL COMMENT '工单 ID',
    trace_id VARCHAR(64) NULL COMMENT '追踪 ID',
    summary TEXT NULL COMMENT '初步判断与建议',
    causes_json JSON NULL COMMENT '可能原因列表',
    actions_json JSON NULL COMMENT '建议动作列表',
    risk_level VARCHAR(16) NULL COMMENT 'LOW/MEDIUM/HIGH',
    citations_json JSON NULL COMMENT '知识依据（doc_id/doc_no/title/score）',
    model VARCHAR(100) NULL COMMENT '模型',
    prompt_version VARCHAR(32) NULL COMMENT '提示词版本',
    latency_ms INT NULL COMMENT '耗时',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by BIGINT NULL COMMENT '创建人 ID',
    PRIMARY KEY (id),
    KEY idx_ticket_ai_analysis_ticket (ticket_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工单 AI 分析';
