-- V6: 文件对象元数据（附件/知识原文）

CREATE TABLE file_object (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    object_key VARCHAR(512) NOT NULL COMMENT 'MinIO 对象键',
    bucket VARCHAR(64) NOT NULL DEFAULT 'agent-gongdan' COMMENT '桶名',
    original_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
    content_type VARCHAR(128) NULL COMMENT 'MIME 类型',
    size_bytes BIGINT NOT NULL COMMENT '文件大小（字节）',
    checksum_sha256 VARCHAR(64) NULL COMMENT 'SHA-256',
    storage_provider VARCHAR(16) NOT NULL DEFAULT 'MINIO' COMMENT '存储提供方',
    biz_type VARCHAR(32) NULL COMMENT '业务类型',
    biz_id BIGINT NULL COMMENT '业务对象 ID',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/PENDING_DELETE/DELETED',
    uploaded_by BIGINT NULL COMMENT '上传人',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    created_by BIGINT NULL COMMENT '创建人 ID',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    updated_by BIGINT NULL COMMENT '更新人 ID',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间（UTC）',
    deleted_by BIGINT NULL COMMENT '删除人 ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_object_key (tenant_id, object_key),
    KEY idx_file_object_checksum (bucket, checksum_sha256),
    KEY idx_file_object_biz (biz_type, biz_id),
    KEY idx_file_object_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件对象元数据';
