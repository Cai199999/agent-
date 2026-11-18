package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("biz_room")
public class BizRoomEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long buildingId;
    private String unitNo;
    private String roomNo;
    private Integer floorNo;
    private BigDecimal area;
    private String usageType;
    private String occupancyStatus;
    private String status;
    private LocalDateTime deletedAt;
}
