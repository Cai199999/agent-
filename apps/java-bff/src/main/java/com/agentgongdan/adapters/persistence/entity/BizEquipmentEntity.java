package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_equipment")
public class BizEquipmentEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long projectId;
    private Long buildingId;
    private Long roomId;
    private String equipmentNo;
    private String equipmentName;
    private String categoryCode;
    private String brand;
    private String model;
    private String locationText;
    private String status;
    private LocalDateTime deletedAt;
}
