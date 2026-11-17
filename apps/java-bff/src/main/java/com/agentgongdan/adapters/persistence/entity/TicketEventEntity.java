package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ticket_event")
public class TicketEventEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long ticketId;
    private String eventType;
    private String fromStatus;
    private String toStatus;
    private Long operatorId;
    private String operatorRole;
    private String reason;
    private String payloadJson;
    private String traceId;
    private LocalDateTime createdAt;
    private Long createdBy;
}
