package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_task")
public class AgentTaskEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String taskId;
    private String agentId;
    private String capability;
    private String status;
    private Long actorId;
    private String actorRoles;
    private String clientTaskId;
    private String inputJson;
    private String resultJson;
    private String errorCode;
    private String errorMessage;
    private String confirmationToken;
    private LocalDateTime confirmationExpiresAt;
    private String traceId;
    private Integer latencyMs;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
