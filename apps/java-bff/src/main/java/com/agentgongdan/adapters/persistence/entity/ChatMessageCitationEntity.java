package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("chat_message_citation")
public class ChatMessageCitationEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long messageId;
    private Long docId;
    private Long chunkId;
    private BigDecimal score;
    private Integer rankNo;
    private LocalDateTime createdAt;
    private Long createdBy;
}
