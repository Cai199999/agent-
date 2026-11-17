package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_audit_log")
public class SysAuditLogEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String traceId;
    private Long actorId;
    private String actorName;
    private String action;
    private String resourceType;
    private String resourceId;
    private String beforeJson;
    private String afterJson;
    private String ip;
    private String userAgent;
    private String result;
    private String errorCode;
    private LocalDateTime createdAt;
    private Long createdBy;
}
