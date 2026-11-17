package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUserEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String username;
    private String passwordHash;
    private String displayName;
    private String status;
    private Integer failedLoginCount;
    private LocalDateTime lockedUntil;
    private Long deletedFlag;
    private LocalDateTime deletedAt;
}
