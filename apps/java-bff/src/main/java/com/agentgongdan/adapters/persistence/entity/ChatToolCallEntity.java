package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_tool_call")
public class ChatToolCallEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long sessionId;
    private Long messageId;
    private String toolCallId;
    private String toolName;
    private String argumentsJson;
    private String resultJson;
    private Long resultTicketId;
    private Integer resultTruncated;
    private Integer resultSizeBytes;
    private String status;
    private String errorCode;
    private Integer latencyMs;
    private String traceId;
    private LocalDateTime createdAt;
    private Long createdBy;
}
