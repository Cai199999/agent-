package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_project")
public class BizProjectEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String projectCode;
    private String projectName;
    private String address;
    private String status;
    private LocalDateTime deletedAt;
}
