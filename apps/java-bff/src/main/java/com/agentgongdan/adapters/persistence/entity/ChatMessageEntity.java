package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessageEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long sessionId;
    private String clientMessageId;
    private String role;
    private String content;
    private String contentType;
    private String status;
    private String traceId;
    private Integer tokenIn;
    private Integer tokenOut;
    private Integer latencyMs;
    private String errorCode;
    private LocalDateTime streamExpiresAt;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private LocalDateTime deletedAt;
    private Long deletedBy;
}
