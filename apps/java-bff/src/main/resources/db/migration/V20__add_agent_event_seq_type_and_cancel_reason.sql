-- V20: 主 Agent 接入 P0 —— 取消原因、事件任务内序号与事件类型
-- 只新增列与索引，不修改既有业务语义，不建物理外键。

ALTER TABLE agent_task
    ADD COLUMN cancel_reason VARCHAR(200) NULL COMMENT '调用方取消原因' AFTER delegation_id;

ALTER TABLE agent_task_event
    ADD COLUMN seq BIGINT NULL COMMENT '任务内事件序号（从 1 递增）' AFTER task_id,
    ADD COLUMN type VARCHAR(40) NULL COMMENT '事件类型（TASK_*）' AFTER seq;

-- 历史事件按插入顺序回填任务内序号。
UPDATE agent_task_event e
JOIN (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY tenant_id, task_id ORDER BY id) AS rn
    FROM agent_task_event
) ordered ON ordered.id = e.id
SET e.seq = ordered.rn;

UPDATE agent_task_event
SET seq = 0
WHERE seq IS NULL;

ALTER TABLE agent_task_event
    MODIFY COLUMN seq BIGINT NOT NULL COMMENT '任务内事件序号（从 1 递增）';

UPDATE agent_task_event
SET type = CASE status
    WHEN 'PENDING' THEN 'TASK_QUEUED'
    WHEN 'WAITING_CONFIRM' THEN 'TASK_CONFIRM_REQUIRED'
    WHEN 'RUNNING' THEN 'TASK_STARTED'
    WHEN 'SUCCEEDED' THEN 'TASK_SUCCEEDED'
    WHEN 'FAILED' THEN 'TASK_FAILED'
    WHEN 'CANCELLED' THEN 'TASK_CANCELLED'
    WHEN 'REJECTED' THEN 'TASK_REJECTED'
    ELSE 'TASK_STATUS_CHANGED'
END
WHERE type IS NULL;

ALTER TABLE agent_task_event
    MODIFY COLUMN type VARCHAR(40) NOT NULL COMMENT '事件类型（TASK_*）';

ALTER TABLE agent_task_event
    ADD UNIQUE KEY uk_agent_task_event_seq (tenant_id, task_id, seq);
