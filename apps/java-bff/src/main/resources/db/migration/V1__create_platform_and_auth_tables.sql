-- V1: 平台与认证
-- 约定：无物理外键；tenant_id 保留；唯一键含 tenant_id；非唯一索引不以 tenant_id 为前缀。

CREATE TABLE sys_user (
    id BIGINT NOT NULL COMMENT '主键，雪花 ID',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID，MVP 固定 0',
    username VARCHAR(64) NOT NULL COMMENT '登录名，租户内唯一',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
    display_name VARCHAR(100) NOT NULL COMMENT '显示名称',
    phone_enc VARBINARY(512) NULL COMMENT '手机号密文',
    phone_hash CHAR(64) NULL COMMENT '手机号 HMAC-SHA256',
    phone_enc_key_version INT NOT NULL DEFAULT 1 COMMENT '手机号加密密钥版本',
    phone_masked VARCHAR(32) NULL COMMENT '手机号脱敏展示值',
    email VARCHAR(128) NULL COMMENT '邮箱',
    dept_id BIGINT NULL COMMENT '所属部门 ID（逻辑外键）',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED/LOCKED',
    last_login_at DATETIME(3) NULL COMMENT '最后登录时间（UTC）',
    last_login_ip VARCHAR(45) NULL COMMENT '最后登录 IP',
    failed_login_count INT NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
    locked_until DATETIME(3) NULL COMMENT '锁定截止时间（UTC）',
    deleted_flag BIGINT NOT NULL DEFAULT 0 COMMENT '可复用唯一键标记：0=有效',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by BIGINT NULL COMMENT '创建人 ID',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    updated_by BIGINT NULL COMMENT '更新人 ID',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间（UTC）',
    deleted_by BIGINT NULL COMMENT '删除人 ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_tenant_username (tenant_id, username, deleted_flag),
    KEY idx_sys_user_dept_status (dept_id, status),
    KEY idx_sys_user_status_deleted (status, deleted_at),
    KEY idx_sys_user_phone_hash (phone_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';

CREATE TABLE sys_role (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    role_code VARCHAR(64) NOT NULL COMMENT '角色编码，租户内唯一',
    role_name VARCHAR(100) NOT NULL COMMENT '角色名称',
    description VARCHAR(500) NULL COMMENT '描述',
    is_builtin TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否内置角色',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by BIGINT NULL COMMENT '创建人 ID',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    updated_by BIGINT NULL COMMENT '更新人 ID',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间（UTC）',
    deleted_by BIGINT NULL COMMENT '删除人 ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_tenant_code (tenant_id, role_code),
    KEY idx_sys_role_status_deleted (status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色表';

CREATE TABLE sys_user_role (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    user_id BIGINT NOT NULL COMMENT '用户 ID',
    role_id BIGINT NOT NULL COMMENT '角色 ID',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by BIGINT NULL COMMENT '操作人 ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_role (tenant_id, user_id, role_id),
    KEY idx_sys_user_role_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关联表';

CREATE TABLE sys_permission (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    permission_code VARCHAR(128) NOT NULL COMMENT '权限编码',
    permission_name VARCHAR(100) NOT NULL COMMENT '权限名称',
    permission_type VARCHAR(16) NOT NULL COMMENT 'MENU/BUTTON/API',
    parent_id BIGINT NULL COMMENT '父权限 ID',
    route_path VARCHAR(255) NULL COMMENT '前端路由',
    http_method VARCHAR(16) NULL COMMENT 'HTTP 方法',
    api_pattern VARCHAR(255) NULL COMMENT 'API 路径模式',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by BIGINT NULL COMMENT '创建人 ID',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    updated_by BIGINT NULL COMMENT '更新人 ID',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间（UTC）',
    deleted_by BIGINT NULL COMMENT '删除人 ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_permission_tenant_code (tenant_id, permission_code),
    KEY idx_sys_permission_parent (parent_id),
    KEY idx_sys_permission_type (permission_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权限点表';

CREATE TABLE sys_role_permission (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    role_id BIGINT NOT NULL COMMENT '角色 ID',
    permission_id BIGINT NOT NULL COMMENT '权限 ID',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by BIGINT NULL COMMENT '操作人 ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_permission (tenant_id, role_id, permission_id),
    KEY idx_sys_role_permission_perm (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色权限关联表';

CREATE TABLE sys_dept (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    parent_id BIGINT NULL COMMENT '父部门 ID',
    dept_code VARCHAR(64) NOT NULL COMMENT '部门编码',
    dept_name VARCHAR(100) NOT NULL COMMENT '部门名称',
    path VARCHAR(500) NULL COMMENT '物化路径',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by BIGINT NULL COMMENT '创建人 ID',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    updated_by BIGINT NULL COMMENT '更新人 ID',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间（UTC）',
    deleted_by BIGINT NULL COMMENT '删除人 ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_dept_tenant_code (tenant_id, dept_code),
    KEY idx_sys_dept_parent (parent_id),
    KEY idx_sys_dept_path (path(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='部门表';

CREATE TABLE idempotency_record (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    idem_key VARCHAR(128) NOT NULL COMMENT '幂等键',
    scope VARCHAR(64) NOT NULL COMMENT '作用域，如 TICKET_CREATE',
    request_hash VARCHAR(64) NULL COMMENT '请求体哈希',
    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING/SUCCESS/FAILED',
    response_code INT NULL COMMENT '业务码',
    response_json JSON NULL COMMENT '成功响应快照',
    expires_at DATETIME(3) NULL COMMENT '过期时间',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_record (tenant_id, scope, idem_key),
    KEY idx_idempotency_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='幂等记录表';

CREATE TABLE biz_sequence (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    seq_name VARCHAR(64) NOT NULL COMMENT '序列名称，如 TICKET_NO',
    current_value BIGINT NOT NULL DEFAULT 0 COMMENT '当前值',
    step INT NOT NULL DEFAULT 100 COMMENT '号段步长',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_sequence_tenant_name (tenant_id, seq_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务序列表';
