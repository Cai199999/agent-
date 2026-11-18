package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user_profile")
public class SysUserProfileEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long userId;
    private String contactName;
    private String phoneEnc;
    private String phoneMask;
    private Long projectId;
    private Long buildingId;
    private Long roomId;
    private Long customerId;
    private String status;
    private String reviewStatus;
    private String reviewRemark;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private LocalDateTime deletedAt;
    private Long deletedBy;
}
