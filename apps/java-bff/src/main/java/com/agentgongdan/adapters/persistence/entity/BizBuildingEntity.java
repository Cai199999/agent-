package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_building")
public class BizBuildingEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long projectId;
    private String buildingCode;
    private String buildingName;
    private Integer floorCount;
    private String status;
    private LocalDateTime deletedAt;
}
