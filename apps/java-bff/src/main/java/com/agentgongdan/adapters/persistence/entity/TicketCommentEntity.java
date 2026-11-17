package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ticket_comment")
public class TicketCommentEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long ticketId;
    private Long parentId;
    private Long authorId;
    private String visibility;
    private String content;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private LocalDateTime deletedAt;
    private Long deletedBy;
}
