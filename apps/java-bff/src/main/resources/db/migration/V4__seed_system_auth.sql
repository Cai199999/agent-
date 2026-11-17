-- V4: 系统角色与权限种子（所有环境）
-- 不包含用户密码；dev 管理员在 db/dev/R__dev_seed_admin.sql。

INSERT INTO sys_role (id, tenant_id, role_code, role_name, description, is_builtin, status)
VALUES
    (1, 0, 'ADMIN', '系统管理员', '全部权限', 1, 'ACTIVE'),
    (2, 0, 'DISPATCHER', '调度员/物业客服', '派单与全局视图', 1, 'ACTIVE'),
    (3, 0, 'ENGINEER', '运维工程师', '接单与处理', 1, 'ACTIVE'),
    (4, 0, 'REPORTER', '报障人', '报障与验证', 1, 'ACTIVE'),
    (5, 0, 'KB_ADMIN', '知识库管理员', '知识维护', 1, 'ACTIVE'),
    (6, 0, 'INSPECTOR', '巡检员', '巡检任务', 1, 'ACTIVE')
ON DUPLICATE KEY UPDATE
    role_name = VALUES(role_name),
    description = VALUES(description),
    status = VALUES(status),
    deleted_at = NULL;

INSERT INTO sys_permission (id, tenant_id, permission_code, permission_name, permission_type, sort_order, status)
VALUES
    (1, 0, 'ticket:create', '创建工单', 'API', 1, 'ACTIVE'),
    (2, 0, 'ticket:read', '查看工单', 'API', 2, 'ACTIVE'),
    (3, 0, 'ticket:update', '编辑工单', 'API', 3, 'ACTIVE'),
    (4, 0, 'ticket:assign', '派单/转派', 'API', 4, 'ACTIVE'),
    (5, 0, 'ticket:claim', '认领工单', 'API', 5, 'ACTIVE'),
    (6, 0, 'ticket:handle', '处理工单', 'API', 6, 'ACTIVE'),
    (7, 0, 'ticket:verify', '验证结果', 'API', 7, 'ACTIVE'),
    (8, 0, 'ticket:close', '关闭/取消', 'API', 8, 'ACTIVE'),
    (9, 0, 'ticket:evaluate', '满意度评价', 'API', 9, 'ACTIVE'),
    (10, 0, 'chat:use', '使用 AI 会话', 'API', 10, 'ACTIVE'),
    (11, 0, 'chat:handoff', '转人工', 'API', 11, 'ACTIVE'),
    (12, 0, 'ai:triage', '调用分诊', 'API', 12, 'ACTIVE'),
    (13, 0, 'ai:similar', '相似工单', 'API', 13, 'ACTIVE'),
    (14, 0, 'knowledge:read', '查看知识', 'API', 14, 'ACTIVE'),
    (15, 0, 'knowledge:manage', '管理知识', 'API', 15, 'ACTIVE'),
    (16, 0, 'dispatch:manage', '派单看板', 'API', 16, 'ACTIVE'),
    (17, 0, 'report:view', '查看报表', 'API', 17, 'ACTIVE'),
    (18, 0, 'customer:read', '查看业户', 'API', 18, 'ACTIVE'),
    (19, 0, 'customer:manage', '管理业户', 'API', 19, 'ACTIVE'),
    (20, 0, 'equipment:read', '查看设备', 'API', 20, 'ACTIVE'),
    (21, 0, 'equipment:manage', '管理设备', 'API', 21, 'ACTIVE'),
    (22, 0, 'admin:user', '用户管理', 'API', 22, 'ACTIVE'),
    (23, 0, 'admin:role', '角色管理', 'API', 23, 'ACTIVE'),
    (24, 0, 'admin:config', '配置管理', 'API', 24, 'ACTIVE'),
    (25, 0, 'admin:audit', '审计日志', 'API', 25, 'ACTIVE')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    status = VALUES(status),
    deleted_at = NULL;

INSERT IGNORE INTO sys_role_permission (id, tenant_id, role_id, permission_id)
VALUES
    (1, 0, 1, 1), (2, 0, 1, 2), (3, 0, 1, 3), (4, 0, 1, 4), (5, 0, 1, 5),
    (6, 0, 1, 6), (7, 0, 1, 7), (8, 0, 1, 8), (9, 0, 1, 9), (10, 0, 1, 10),
    (11, 0, 1, 11), (12, 0, 1, 12), (13, 0, 1, 13), (14, 0, 1, 14), (15, 0, 1, 15),
    (16, 0, 1, 16), (17, 0, 1, 17), (18, 0, 1, 18), (19, 0, 1, 19), (20, 0, 1, 20),
    (21, 0, 1, 21), (22, 0, 1, 22), (23, 0, 1, 23), (24, 0, 1, 24), (25, 0, 1, 25);
