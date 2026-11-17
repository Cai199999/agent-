package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_session_ticket")
public class ChatSessionTicketEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long sessionId;
    private Long ticketId;
    private String relationType;
    private String createdToolCallId;
    private LocalDateTime createdAt;
    private Long createdBy;
}
