package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_user_role")
public class SysUserRoleEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long userId;
    private Long roleId;
}
