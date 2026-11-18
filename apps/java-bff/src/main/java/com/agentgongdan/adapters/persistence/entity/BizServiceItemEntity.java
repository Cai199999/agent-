package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_service_item")
public class BizServiceItemEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String itemCode;
    private String itemName;
    private String categoryCode;
    private String defaultPriority;
    private String status;
    private LocalDateTime deletedAt;
}
