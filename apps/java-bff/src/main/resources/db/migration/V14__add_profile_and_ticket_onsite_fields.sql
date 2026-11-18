-- V14: 客户端个人信息中心 + 工单室内外/现场情况/服务项文本
-- 逻辑外键 + 索引；不建物理外键。

ALTER TABLE ticket
    ADD COLUMN location_type VARCHAR(16) NULL COMMENT 'INDOOR/OUTDOOR' AFTER location_text,
    ADD COLUMN service_item_text VARCHAR(200) NULL COMMENT '服务项目文本（处理人填写）' AFTER service_item_id,
    ADD COLUMN onsite_situation VARCHAR(1000) NULL COMMENT '处理人现场情况' AFTER arrived_at;

CREATE TABLE sys_user_profile (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    user_id BIGINT NOT NULL COMMENT '用户 ID（逻辑外键）',
    contact_name VARCHAR(100) NULL COMMENT '联系人姓名',
    phone_enc VARCHAR(255) NULL COMMENT '加密电话',
    phone_mask VARCHAR(32) NULL COMMENT '脱敏电话',
    project_id BIGINT NULL COMMENT '项目 ID（逻辑外键）',
    building_id BIGINT NULL COMMENT '楼宇 ID（逻辑外键）',
    room_id BIGINT NULL COMMENT '房间 ID（逻辑外键）',
    customer_id BIGINT NULL COMMENT '客户 ID（逻辑外键）',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    created_by BIGINT NULL COMMENT '创建人',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    updated_by BIGINT NULL COMMENT '更新人',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间',
    deleted_by BIGINT NULL COMMENT '删除人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_profile_user (tenant_id, user_id),
    KEY idx_sys_user_profile_project (project_id),
    KEY idx_sys_user_profile_customer (customer_id),
    KEY idx_sys_user_profile_status (status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户个人资料（报修人联系信息）';
