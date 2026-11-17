package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ticket_ai_analysis")
public class TicketAiAnalysisEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long ticketId;
    private String traceId;
    private String summary;
    private String causesJson;
    private String actionsJson;
    private String riskLevel;
    private String citationsJson;
    private String model;
    private String promptVersion;
    private Integer latencyMs;
    private LocalDateTime createdAt;
    private Long createdBy;
}
