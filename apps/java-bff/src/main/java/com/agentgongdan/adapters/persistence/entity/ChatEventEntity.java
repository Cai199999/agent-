package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_event")
public class ChatEventEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long sessionId;
    private String eventType;
    private Long operatorId;
    private String payloadJson;
    private LocalDateTime createdAt;
    private Long createdBy;
}
