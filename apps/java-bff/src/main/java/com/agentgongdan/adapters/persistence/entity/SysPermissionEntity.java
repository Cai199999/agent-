package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_permission")
public class SysPermissionEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String permissionCode;
    private String status;
    private LocalDateTime deletedAt;
}
