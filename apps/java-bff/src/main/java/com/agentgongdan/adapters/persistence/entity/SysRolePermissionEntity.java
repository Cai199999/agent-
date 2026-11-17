package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_role_permission")
public class SysRolePermissionEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long roleId;
    private Long permissionId;
}
