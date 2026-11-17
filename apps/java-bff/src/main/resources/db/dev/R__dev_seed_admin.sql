-- dev-only：管理员种子（密码在项目根 .env 的 DEV_ADMIN_PASSWORD，SQL 仅存 BCrypt 哈希）
-- 该文件位于 db/dev，仅在 dev profile 加载；生产不加载。

INSERT INTO sys_user (id, tenant_id, username, password_hash, display_name, status, deleted_flag)
VALUES (10001, 0, 'admin', '$2b$10$2AmBgAbzwHzu.iah2eYGkORq81rkGcKqnRZsXvduFUq92i9uCTXqW', '系统管理员', 'ACTIVE', 0)
ON DUPLICATE KEY UPDATE
    password_hash = VALUES(password_hash),
    display_name = VALUES(display_name),
    status = 'ACTIVE',
    deleted_flag = 0,
    deleted_at = NULL;

INSERT IGNORE INTO sys_user_role (id, tenant_id, user_id, role_id)
VALUES (1, 0, 10001, 1);
