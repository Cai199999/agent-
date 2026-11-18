package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_task_event")
public class AgentTaskEventEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String taskId;
    private String status;
    private String message;
    private LocalDateTime createdAt;
}
