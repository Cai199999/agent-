-- V5: 操作审计日志（追加型，不软删除）

CREATE TABLE sys_audit_log (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    trace_id VARCHAR(64) NULL COMMENT '追踪 ID',
    actor_id BIGINT NULL COMMENT '操作人 ID',
    actor_name VARCHAR(100) NULL COMMENT '操作人名称快照',
    action VARCHAR(64) NOT NULL COMMENT '动作编码',
    resource_type VARCHAR(64) NULL COMMENT '资源类型',
    resource_id VARCHAR(64) NULL COMMENT '资源 ID',
    before_json JSON NULL COMMENT '变更前（脱敏）',
    after_json JSON NULL COMMENT '变更后（脱敏）',
    ip VARCHAR(45) NULL COMMENT '来源 IP',
    user_agent VARCHAR(512) NULL COMMENT 'User-Agent',
    result VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED',
    error_code VARCHAR(64) NULL COMMENT '错误码',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by BIGINT NULL COMMENT '操作人 ID',
    PRIMARY KEY (id),
    KEY idx_sys_audit_actor (actor_id, created_at),
    KEY idx_sys_audit_trace (trace_id),
    KEY idx_sys_audit_resource (resource_type, resource_id, created_at),
    KEY idx_sys_audit_action (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作审计日志';
