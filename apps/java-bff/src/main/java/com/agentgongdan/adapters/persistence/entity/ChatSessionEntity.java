package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_session")
public class ChatSessionEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String sessionNo;
    private Long userId;
    private String title;
    private String status;
    private Long ticketId;
    private Integer messageCount;
    private LocalDateTime lastMessageAt;
    private LocalDateTime streamExpiresAt;
    private String summary;
    private Long summaryCoveredMessageId;
    private Integer version;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private LocalDateTime deletedAt;
    private Long deletedBy;
}
