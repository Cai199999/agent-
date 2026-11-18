-- V12: 物业主数据 + 工单扩展（Phase 2 基础模型）
-- 原则：逻辑外键 + 索引；不建物理外键；不复制现有 ticket/ticket_event/ticket_assignment。

CREATE TABLE biz_project (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    project_code VARCHAR(64) NOT NULL COMMENT '项目编码',
    project_name VARCHAR(200) NOT NULL COMMENT '项目名称',
    address VARCHAR(500) NULL COMMENT '项目地址',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    created_by BIGINT NULL COMMENT '创建人',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    updated_by BIGINT NULL COMMENT '更新人',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间',
    deleted_by BIGINT NULL COMMENT '删除人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_project_code (tenant_id, project_code),
    KEY idx_biz_project_status (status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目';

CREATE TABLE biz_building (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    project_id BIGINT NOT NULL COMMENT '项目 ID（逻辑外键）',
    building_code VARCHAR(64) NOT NULL COMMENT '楼宇编码',
    building_name VARCHAR(100) NOT NULL COMMENT '楼宇名称',
    floor_count INT NULL COMMENT '楼层数',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    created_by BIGINT NULL COMMENT '创建人',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    updated_by BIGINT NULL COMMENT '更新人',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间',
    deleted_by BIGINT NULL COMMENT '删除人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_building_code (tenant_id, project_id, building_code),
    KEY idx_biz_building_project (project_id, status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='楼宇';

CREATE TABLE biz_room (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    building_id BIGINT NOT NULL COMMENT '楼宇 ID（逻辑外键）',
    unit_no VARCHAR(32) NULL COMMENT '单元',
    room_no VARCHAR(64) NOT NULL COMMENT '房号',
    floor_no INT NULL COMMENT '楼层',
    area DECIMAL(12,2) NULL COMMENT '面积',
    usage_type VARCHAR(32) NULL COMMENT 'RESIDENTIAL/OFFICE/COMMERCIAL/PUBLIC',
    occupancy_status VARCHAR(16) NOT NULL DEFAULT 'VACANT' COMMENT 'VACANT/OCCUPIED/RENTED/RENOVATING',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    created_by BIGINT NULL COMMENT '创建人',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    updated_by BIGINT NULL COMMENT '更新人',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间',
    deleted_by BIGINT NULL COMMENT '删除人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_room_no (tenant_id, building_id, room_no),
    KEY idx_biz_room_building (building_id, status, deleted_at),
    KEY idx_biz_room_occupancy (occupancy_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='房间/房号';

CREATE TABLE biz_customer (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    project_id BIGINT NOT NULL COMMENT '项目 ID（逻辑外键）',
    customer_no VARCHAR(64) NOT NULL COMMENT '客户编号',
    customer_name VARCHAR(200) NOT NULL COMMENT '客户名称',
    customer_type VARCHAR(32) NOT NULL DEFAULT 'OWNER' COMMENT 'OWNER/TENANT/VISITOR/COMPANY',
    contact_name VARCHAR(100) NULL COMMENT '联系人',
    phone_enc VARCHAR(255) NULL COMMENT '加密电话',
    phone_mask VARCHAR(32) NULL COMMENT '脱敏电话',
    email VARCHAR(200) NULL COMMENT '邮箱',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    created_by BIGINT NULL COMMENT '创建人',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    updated_by BIGINT NULL COMMENT '更新人',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间',
    deleted_by BIGINT NULL COMMENT '删除人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_customer_no (tenant_id, customer_no),
    KEY idx_biz_customer_project (project_id, status, deleted_at),
    KEY idx_biz_customer_name (customer_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业户/客户';

CREATE TABLE biz_customer_room (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    customer_id BIGINT NOT NULL COMMENT '客户 ID（逻辑外键）',
    room_id BIGINT NOT NULL COMMENT '房间 ID（逻辑外键）',
    relation_type VARCHAR(16) NOT NULL DEFAULT 'OWNER' COMMENT 'OWNER/TENANT/FAMILY',
    start_at DATETIME(3) NULL COMMENT '开始时间',
    end_at DATETIME(3) NULL COMMENT '结束时间',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    created_by BIGINT NULL COMMENT '创建人',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    updated_by BIGINT NULL COMMENT '更新人',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间',
    deleted_by BIGINT NULL COMMENT '删除人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_customer_room (tenant_id, customer_id, room_id, relation_type),
    KEY idx_biz_customer_room_room (room_id, status, deleted_at),
    KEY idx_biz_customer_room_customer (customer_id, status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业户-房间关系';

CREATE TABLE biz_equipment (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    project_id BIGINT NOT NULL COMMENT '项目 ID（逻辑外键）',
    building_id BIGINT NULL COMMENT '楼宇 ID（逻辑外键）',
    room_id BIGINT NULL COMMENT '房间 ID（逻辑外键，可空）',
    equipment_no VARCHAR(64) NOT NULL COMMENT '设备编号',
    equipment_name VARCHAR(200) NOT NULL COMMENT '设备名称',
    category_code VARCHAR(32) NOT NULL COMMENT 'ELEVATOR/HVAC/PUMP/FIRE/ACCESS/NETWORK/ELECTRICAL/OTHER',
    brand VARCHAR(100) NULL COMMENT '品牌',
    model VARCHAR(100) NULL COMMENT '型号',
    location_text VARCHAR(255) NULL COMMENT '安装位置',
    status VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/FAULT/MAINTENANCE/SCRAPPED',
    installed_at DATETIME(3) NULL COMMENT '安装时间',
    warranty_end DATETIME(3) NULL COMMENT '质保到期',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    created_by BIGINT NULL COMMENT '创建人',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    updated_by BIGINT NULL COMMENT '更新人',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间',
    deleted_by BIGINT NULL COMMENT '删除人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_equipment_no (tenant_id, equipment_no),
    KEY idx_biz_equipment_project (project_id, status, deleted_at),
    KEY idx_biz_equipment_building (building_id, status, deleted_at),
    KEY idx_biz_equipment_category (category_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='设备台账';

CREATE TABLE biz_service_item (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    item_code VARCHAR(64) NOT NULL COMMENT '服务项目编码',
    item_name VARCHAR(200) NOT NULL COMMENT '服务项目名称',
    category_code VARCHAR(32) NULL COMMENT '服务分类',
    default_priority VARCHAR(16) NULL COMMENT '默认优先级',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    created_by BIGINT NULL COMMENT '创建人',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    updated_by BIGINT NULL COMMENT '更新人',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间',
    deleted_by BIGINT NULL COMMENT '删除人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_service_item_code (tenant_id, item_code),
    KEY idx_biz_service_item_status (status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工单服务项目';

CREATE TABLE biz_workflow (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    workflow_code VARCHAR(64) NOT NULL COMMENT '流程编码',
    workflow_name VARCHAR(200) NOT NULL COMMENT '流程名称',
    ticket_type_code VARCHAR(32) NULL COMMENT '适用工单类型',
    version INT NOT NULL DEFAULT 1 COMMENT '流程版本',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    created_by BIGINT NULL COMMENT '创建人',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    updated_by BIGINT NULL COMMENT '更新人',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间',
    deleted_by BIGINT NULL COMMENT '删除人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_workflow_code (tenant_id, workflow_code, version),
    KEY idx_biz_workflow_status (status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工单流程';

CREATE TABLE biz_workflow_node (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    workflow_id BIGINT NOT NULL COMMENT '流程 ID（逻辑外键）',
    node_code VARCHAR(32) NOT NULL COMMENT '节点编码',
    node_name VARCHAR(100) NOT NULL COMMENT '节点名称',
    seq_no INT NOT NULL DEFAULT 0 COMMENT '顺序',
    role_code VARCHAR(32) NULL COMMENT '处理角色',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    created_by BIGINT NULL COMMENT '创建人',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    updated_by BIGINT NULL COMMENT '更新人',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间',
    deleted_by BIGINT NULL COMMENT '删除人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_workflow_node (tenant_id, workflow_id, node_code),
    KEY idx_biz_workflow_node_seq (workflow_id, seq_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工单流程节点';

ALTER TABLE ticket
    ADD COLUMN project_id BIGINT NULL COMMENT '项目 ID（逻辑外键）' AFTER tenant_id,
    ADD COLUMN building_id BIGINT NULL COMMENT '楼宇 ID（逻辑外键）' AFTER project_id,
    ADD COLUMN service_item_id BIGINT NULL COMMENT '服务项目 ID（逻辑外键）' AFTER equipment_id,
    ADD COLUMN workflow_id BIGINT NULL COMMENT '流程 ID（逻辑外键）' AFTER service_item_id,
    ADD COLUMN current_node_code VARCHAR(32) NULL COMMENT '当前流程节点编码' AFTER workflow_id,
    ADD COLUMN appointment_at DATETIME(3) NULL COMMENT '预约时间（UTC）' AFTER current_node_code,
    ADD COLUMN received_at DATETIME(3) NULL COMMENT '收单时间（UTC）' AFTER appointment_at,
    ADD COLUMN arrived_at DATETIME(3) NULL COMMENT '到场时间（UTC）' AFTER received_at,
    ADD COLUMN task_due_at DATETIME(3) NULL COMMENT '任务要求完成时间（UTC）' AFTER arrived_at,
    ADD COLUMN contact_name VARCHAR(100) NULL COMMENT '联系人快照' AFTER task_due_at,
    ADD COLUMN contact_phone_enc VARCHAR(255) NULL COMMENT '联系电话加密快照' AFTER contact_name,
    ADD COLUMN contact_phone_mask VARCHAR(32) NULL COMMENT '联系电话脱敏快照' AFTER contact_phone_enc,
    ADD COLUMN address_text VARCHAR(500) NULL COMMENT '详细地址快照' AFTER contact_phone_mask,
    ADD COLUMN indoor_public_area VARCHAR(16) NULL COMMENT 'INDOOR/PUBLIC' AFTER address_text,
    ADD COLUMN incomplete_reason_code VARCHAR(32) NULL COMMENT '未完成原因编码' AFTER cancel_reason,
    ADD KEY idx_ticket_project (project_id),
    ADD KEY idx_ticket_building (building_id),
    ADD KEY idx_ticket_service_item (service_item_id),
    ADD KEY idx_ticket_workflow (workflow_id);

ALTER TABLE ticket_event
    ADD COLUMN operator_dept_id BIGINT NULL COMMENT '操作人部门 ID（逻辑外键）' AFTER operator_role,
    ADD KEY idx_ticket_event_dept (operator_dept_id, created_at);

CREATE TABLE ticket_material (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    ticket_id BIGINT NOT NULL COMMENT '工单 ID（逻辑外键）',
    material_name VARCHAR(200) NOT NULL COMMENT '材料名称',
    specification VARCHAR(200) NULL COMMENT '规格型号',
    quantity DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '数量',
    unit VARCHAR(32) NULL COMMENT '单位',
    amount DECIMAL(12,2) NULL COMMENT '金额',
    remark VARCHAR(500) NULL COMMENT '备注',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    created_by BIGINT NULL COMMENT '创建人',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    updated_by BIGINT NULL COMMENT '更新人',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间',
    deleted_by BIGINT NULL COMMENT '删除人',
    PRIMARY KEY (id),
    KEY idx_ticket_material_ticket (ticket_id, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工单使用材料';

CREATE TABLE ticket_visit (
    id BIGINT NOT NULL COMMENT '主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID',
    ticket_id BIGINT NOT NULL COMMENT '工单 ID（逻辑外键）',
    required_flag TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否要求回访',
    visit_method VARCHAR(32) NULL COMMENT 'PHONE/VISIT/WECHAT/OTHER',
    visitor_id BIGINT NULL COMMENT '回访人 ID（逻辑外键）',
    visited_at DATETIME(3) NULL COMMENT '回访时间',
    visit_result VARCHAR(500) NULL COMMENT '回访结果',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DONE/FAILED',
    remark VARCHAR(500) NULL COMMENT '备注',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    created_by BIGINT NULL COMMENT '创建人',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    updated_by BIGINT NULL COMMENT '更新人',
    deleted_at DATETIME(3) NULL COMMENT '软删除时间',
    deleted_by BIGINT NULL COMMENT '删除人',
    PRIMARY KEY (id),
    KEY idx_ticket_visit_ticket (ticket_id, status, deleted_at),
    KEY idx_ticket_visit_visitor (visitor_id, visited_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工单回访';
