package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_notification")
public class SysNotificationEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long recipientId;
    private String typeCode;
    private String title;
    private String content;
    private String bizType;
    private Long bizId;
    private String channel;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private LocalDateTime deletedAt;
    private Long deletedBy;
}
