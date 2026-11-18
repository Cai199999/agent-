-- dev-only：物业主数据演示种子（幂等，重复执行安全）
-- 仅 dev profile 加载；不修改生产表结构，不建物理外键。
-- V13：房间唯一键包含 unit_no，允许同楼宇不同单元存在相同房号。

INSERT IGNORE INTO sys_dept (id, tenant_id, parent_id, dept_code, dept_name, path, sort_order, status)
VALUES
    (1, 0, NULL, 'PROPERTY', '物业部', '/1/', 1, 'ACTIVE'),
    (2, 0, NULL, 'ENGINEERING', '工程部', '/2/', 2, 'ACTIVE'),
    (3, 0, NULL, 'SECURITY', '安保部', '/3/', 3, 'ACTIVE');

UPDATE sys_user SET dept_id = 1 WHERE id = 10001 AND dept_id IS NULL;
UPDATE sys_user SET dept_id = 2 WHERE id = 10002 AND dept_id IS NULL;
UPDATE sys_user SET dept_id = 1 WHERE id = 10003 AND dept_id IS NULL;

INSERT IGNORE INTO biz_project (id, tenant_id, project_code, project_name, address, status)
VALUES (1, 0, 'P001', '阳光花园', '示范路 88 号', 'ACTIVE');

INSERT IGNORE INTO biz_building (id, tenant_id, project_id, building_code, building_name, floor_count, status)
VALUES
    (101, 0, 1, 'A', 'A 栋', 18, 'ACTIVE'),
    (102, 0, 1, 'B', 'B 栋', 18, 'ACTIVE'),
    (103, 0, 1, 'C', 'C 栋', 12, 'ACTIVE'),
    (104, 0, 1, 'D', 'D 栋', 12, 'ACTIVE'),
    (105, 0, 1, 'E', 'E 栋', 12, 'ACTIVE'),
    (106, 0, 1, 'F', 'F 栋', 12, 'ACTIVE'),
    (107, 0, 1, 'G', 'G 栋', 12, 'ACTIVE');

INSERT IGNORE INTO biz_room (id, tenant_id, building_id, unit_no, room_no, floor_no, area, usage_type, occupancy_status, status)
VALUES
    (1001, 0, 101, '1', '101', 1, 89.00, 'RESIDENTIAL', 'OCCUPIED', 'ACTIVE'),
    (1002, 0, 101, '1', '201', 2, 89.00, 'RESIDENTIAL', 'OCCUPIED', 'ACTIVE'),
    (1003, 0, 101, '2', '101', 1, 120.00, 'RESIDENTIAL', 'OCCUPIED', 'ACTIVE'),
    (1004, 0, 102, '1', '101', 1, 89.00, 'RESIDENTIAL', 'VACANT', 'ACTIVE'),
    (1005, 0, 103, '1', '201', 2, 95.00, 'OFFICE', 'RENTED', 'ACTIVE'),
    (1006, 0, 104, '1', '101', 1, 89.00, 'RESIDENTIAL', 'OCCUPIED', 'ACTIVE'),
    (1007, 0, 105, '1', '101', 1, 89.00, 'RESIDENTIAL', 'OCCUPIED', 'ACTIVE'),
    (1008, 0, 106, '1', '101', 1, 89.00, 'RESIDENTIAL', 'OCCUPIED', 'ACTIVE'),
    (1009, 0, 107, '1', '101', 1, 89.00, 'RESIDENTIAL', 'OCCUPIED', 'ACTIVE'),
    (1010, 0, 101, '0', '大堂', 1, NULL, 'PUBLIC', 'OCCUPIED', 'ACTIVE');

INSERT IGNORE INTO biz_customer (id, tenant_id, project_id, customer_no, customer_name, customer_type,
                                 contact_name, phone_mask, status)
VALUES
    (2001, 0, 1, 'C001', '李业主', 'OWNER', '李业主', '138****0001', 'ACTIVE'),
    (2002, 0, 1, 'C002', '王先生', 'OWNER', '王先生', '139****0002', 'ACTIVE'),
    (2003, 0, 1, 'C003', '陈女士', 'TENANT', '陈女士', '137****0003', 'ACTIVE');

INSERT IGNORE INTO biz_customer_room (id, tenant_id, customer_id, room_id, relation_type, status)
VALUES
    (6001, 0, 2001, 1001, 'OWNER', 'ACTIVE'),
    (6002, 0, 2001, 1002, 'OWNER', 'ACTIVE'),
    (6003, 0, 2002, 1003, 'OWNER', 'ACTIVE'),
    (6004, 0, 2003, 1005, 'TENANT', 'ACTIVE');

INSERT IGNORE INTO biz_equipment (id, tenant_id, project_id, building_id, room_id, equipment_no,
                                  equipment_name, category_code, brand, model, location_text, status)
VALUES
    (3001, 0, 1, 101, NULL, 'EQ-A-002', 'A栋2号电梯', 'ELEVATOR', '日立', 'HGE-1000', 'A栋2号梯', 'NORMAL'),
    (3002, 0, 1, 102, NULL, 'EQ-B-001', 'B栋1号电梯', 'ELEVATOR', '通力', 'KONE-800', 'B栋1号梯', 'NORMAL'),
    (3003, 0, 1, 101, NULL, 'EQ-FIRE-001', '消防水泵1号泵', 'PUMP', '南方泵业', 'XBD-50', '消防水泵房', 'NORMAL'),
    (3004, 0, 1, 101, 1010, 'EQ-ACC-001', 'A栋大堂门禁', 'ACCESS', '海康', 'DS-K1T', 'A栋大堂', 'NORMAL'),
    (3005, 0, 1, 103, 1005, 'EQ-HVAC-001', 'C栋201空调', 'HVAC', '格力', 'GMV-120', 'C栋201', 'NORMAL');

INSERT IGNORE INTO biz_service_item (id, tenant_id, item_code, item_name, category_code, default_priority, status)
VALUES
    (4001, 0, 'SVC-ELEVATOR-TRAP', '电梯困人救援', 'ELEVATOR', 'URGENT', 'ACTIVE'),
    (4002, 0, 'SVC-ELEVATOR-NOISE', '电梯异响检修', 'ELEVATOR', 'HIGH', 'ACTIVE'),
    (4003, 0, 'SVC-FIRE-PUMP', '消防水泵巡检', 'FIRE', 'HIGH', 'ACTIVE'),
    (4004, 0, 'SVC-ACCESS', '门禁道闸故障', 'ACCESS', 'HIGH', 'ACTIVE'),
    (4005, 0, 'SVC-HVAC', '空调不制冷', 'HVAC', 'NORMAL', 'ACTIVE');

INSERT IGNORE INTO biz_workflow (id, tenant_id, workflow_code, workflow_name, ticket_type_code, version, status)
VALUES (5001, 0, 'WF-PROPERTY-REPAIR', '物业报修标准流程', NULL, 1, 'ACTIVE');

INSERT IGNORE INTO biz_workflow_node (id, tenant_id, workflow_id, node_code, node_name, seq_no, role_code, status)
VALUES
    (5101, 0, 5001, 'ACCEPT', '受理', 1, 'PROPERTY', 'ACTIVE'),
    (5102, 0, 5001, 'DISPATCH', '派单', 2, 'PROPERTY', 'ACTIVE'),
    (5103, 0, 5001, 'HANDLE', '处理', 3, 'ENGINEER', 'ACTIVE'),
    (5104, 0, 5001, 'VERIFY', '验证', 4, 'PROPERTY', 'ACTIVE'),
    (5105, 0, 5001, 'CLOSE', '关闭', 5, 'PROPERTY', 'ACTIVE');

-- 仅回填 dev 演示数据中的空值，不覆盖业务已填写内容。
UPDATE ticket SET project_id = 1 WHERE project_id IS NULL;
UPDATE ticket
SET building_id = CASE
    WHEN location_text LIKE 'A%' THEN 101
    WHEN location_text LIKE 'B%' THEN 102
    WHEN location_text LIKE 'C%' THEN 103
    WHEN location_text LIKE 'D%' THEN 104
    WHEN location_text LIKE 'E%' THEN 105
    WHEN location_text LIKE 'F%' THEN 106
    WHEN location_text LIKE 'G%' THEN 107
    ELSE NULL
END
WHERE building_id IS NULL;
UPDATE ticket SET workflow_id = 5001 WHERE workflow_id IS NULL;
UPDATE ticket
SET current_node_code = CASE status_code
    WHEN 'PENDING' THEN 'ACCEPT'
    WHEN 'PENDING_ASSIGN' THEN 'DISPATCH'
    WHEN 'PENDING_ACCEPT' THEN 'DISPATCH'
    WHEN 'PROCESSING' THEN 'HANDLE'
    WHEN 'SUSPENDED' THEN 'HANDLE'
    WHEN 'PENDING_VERIFY' THEN 'VERIFY'
    WHEN 'RESOLVED' THEN 'CLOSE'
    WHEN 'CLOSED' THEN 'CLOSE'
    WHEN 'CANCELLED' THEN 'CLOSE'
    ELSE 'ACCEPT'
END
WHERE current_node_code IS NULL;
UPDATE ticket
SET service_item_id = CASE ticket_type_code
    WHEN 'ELEVATOR' THEN 4001
    WHEN 'FIRE' THEN 4003
    WHEN 'ACCESS' THEN 4004
    WHEN 'HVAC' THEN 4005
    ELSE NULL
END
WHERE service_item_id IS NULL;
UPDATE ticket SET task_due_at = sla_resolve_due_at
WHERE task_due_at IS NULL AND sla_resolve_due_at IS NOT NULL;
UPDATE ticket_event e
SET operator_dept_id = (
    SELECT u.dept_id FROM sys_user u WHERE u.id = e.operator_id LIMIT 1
)
WHERE e.operator_dept_id IS NULL AND e.operator_id IS NOT NULL;
