-- dev-only：演示用户（工程师 / 报障人），复用 admin 的 BCrypt 哈希与 .env 密码
-- 仅 dev profile 加载；生产不加载。

INSERT INTO sys_user (id, tenant_id, username, password_hash, display_name, status, deleted_flag)
SELECT 10002, 0, 'engineer1', password_hash, '张工（运维）', 'ACTIVE', 0
FROM sys_user WHERE id = 10001
ON DUPLICATE KEY UPDATE
    password_hash = VALUES(password_hash),
    display_name = VALUES(display_name),
    status = 'ACTIVE',
    deleted_flag = 0,
    deleted_at = NULL;

INSERT INTO sys_user (id, tenant_id, username, password_hash, display_name, status, deleted_flag)
SELECT 10003, 0, 'reporter1', password_hash, '李业主', 'ACTIVE', 0
FROM sys_user WHERE id = 10001
ON DUPLICATE KEY UPDATE
    password_hash = VALUES(password_hash),
    display_name = VALUES(display_name),
    status = 'ACTIVE',
    deleted_flag = 0,
    deleted_at = NULL;

INSERT IGNORE INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES
    (2, 0, 10002, 3),
    (3, 0, 10003, 4);
