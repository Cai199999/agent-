-- V9: 站内通知（轮询版，SSE 后续接入）

CREATE TABLE sys_notification (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    recipient_id BIGINT NOT NULL COMMENT '接收人 ID',
    type_code VARCHAR(32) NOT NULL COMMENT '通知类型',
    title VARCHAR(200) NOT NULL COMMENT '标题',
    content VARCHAR(1000) NULL COMMENT '内容',
    biz_type VARCHAR(32) NULL COMMENT '业务类型',
    biz_id BIGINT NULL COMMENT '业务 ID',
    channel VARCHAR(16) NOT NULL DEFAULT 'IN_APP' COMMENT 'IN_APP/EMAIL/IM',
    read_at DATETIME(3) NULL COMMENT '已读时间（UTC）',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by BIGINT NULL COMMENT '创建人 ID',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    updated_by BIGINT NULL COMMENT '更新人 ID',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间（UTC）',
    deleted_by BIGINT NULL COMMENT '删除人 ID',
    PRIMARY KEY (id),
    KEY idx_sys_notification_recipient (recipient_id, read_at, created_at),
    KEY idx_sys_notification_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内通知';
