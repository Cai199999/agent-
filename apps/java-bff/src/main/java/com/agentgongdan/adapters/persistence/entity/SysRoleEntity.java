package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_role")
public class SysRoleEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String roleCode;
    private String roleName;
    private String status;
    private LocalDateTime deletedAt;
}
