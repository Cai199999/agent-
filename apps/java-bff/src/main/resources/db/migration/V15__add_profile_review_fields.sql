-- V15: 个人资料绑定审核状态
ALTER TABLE sys_user_profile
    ADD COLUMN review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED' AFTER status,
    ADD COLUMN review_remark VARCHAR(500) NULL COMMENT '审核备注' AFTER review_status,
    ADD COLUMN reviewed_by BIGINT NULL COMMENT '审核人' AFTER review_remark,
    ADD COLUMN reviewed_at DATETIME(3) NULL COMMENT '审核时间' AFTER reviewed_by,
    ADD KEY idx_sys_user_profile_review (review_status, updated_at);

-- 现有 dev 管理员资料视为已审核，避免阻断演示。
UPDATE sys_user_profile
SET review_status = 'APPROVED', reviewed_by = 10001, reviewed_at = NOW(3)
WHERE user_id = 10001 AND review_status = 'PENDING';
