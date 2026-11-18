package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ticket_visit")
public class TicketVisitEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long ticketId;
    private Integer requiredFlag;
    private String visitMethod;
    private Long visitorId;
    private LocalDateTime visitedAt;
    private String visitResult;
    private String status;
    private String remark;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private LocalDateTime deletedAt;
    private Long deletedBy;
}
