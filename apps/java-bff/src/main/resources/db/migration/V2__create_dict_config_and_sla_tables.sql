-- V2: 字典、配置与 SLA

CREATE TABLE sys_dict_type (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    type_code VARCHAR(64) NOT NULL COMMENT '字典类型编码',
    type_name VARCHAR(100) NOT NULL COMMENT '字典类型名称',
    description VARCHAR(500) NULL COMMENT '描述',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by BIGINT NULL COMMENT '创建人 ID',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    updated_by BIGINT NULL COMMENT '更新人 ID',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间（UTC）',
    deleted_by BIGINT NULL COMMENT '删除人 ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_dict_type_tenant_code (tenant_id, type_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典类型表';

CREATE TABLE sys_dict_item (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    type_code VARCHAR(64) NOT NULL COMMENT '字典类型编码',
    item_code VARCHAR(64) NOT NULL COMMENT '字典项编码',
    item_name VARCHAR(100) NOT NULL COMMENT '字典项名称',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
    ext_json JSON NULL COMMENT '展示扩展',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by BIGINT NULL COMMENT '创建人 ID',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    updated_by BIGINT NULL COMMENT '更新人 ID',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间（UTC）',
    deleted_by BIGINT NULL COMMENT '删除人 ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_dict_item (tenant_id, type_code, item_code),
    KEY idx_sys_dict_item_query (type_code, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典项表';

CREATE TABLE sys_config (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    config_key VARCHAR(128) NOT NULL COMMENT '配置键',
    config_value TEXT NULL COMMENT '配置值',
    value_type VARCHAR(16) NOT NULL DEFAULT 'STRING' COMMENT 'STRING/INT/BOOL/JSON',
    description VARCHAR(500) NULL COMMENT '描述',
    is_sensitive TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否敏感',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by BIGINT NULL COMMENT '创建人 ID',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    updated_by BIGINT NULL COMMENT '更新人 ID',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间（UTC）',
    deleted_by BIGINT NULL COMMENT '删除人 ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_config_tenant_key (tenant_id, config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务配置表';

CREATE TABLE cfg_sla_policy (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    policy_code VARCHAR(64) NOT NULL COMMENT '策略编码',
    policy_name VARCHAR(100) NOT NULL COMMENT '策略名称',
    priority_code VARCHAR(16) NOT NULL COMMENT '适用优先级',
    ticket_type_code VARCHAR(32) NULL COMMENT '适用工单类型，空表示全部',
    response_minutes INT NOT NULL COMMENT '首次响应时限（分钟）',
    resolve_minutes INT NOT NULL COMMENT '解决时限（分钟）',
    calendar_type VARCHAR(16) NOT NULL DEFAULT '24x7' COMMENT '日历类型',
    pause_on_hold TINYINT(1) NOT NULL DEFAULT 1 COMMENT '挂起期间是否暂停 SLA',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    effective_from DATETIME(3) NULL COMMENT '生效开始（UTC）',
    effective_to DATETIME(3) NULL COMMENT '生效结束（UTC）',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by BIGINT NULL COMMENT '创建人 ID',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    updated_by BIGINT NULL COMMENT '更新人 ID',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间（UTC）',
    deleted_by BIGINT NULL COMMENT '删除人 ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cfg_sla_policy_tenant_code (tenant_id, policy_code),
    KEY idx_cfg_sla_policy_query (priority_code, ticket_type_code, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SLA 策略表';
