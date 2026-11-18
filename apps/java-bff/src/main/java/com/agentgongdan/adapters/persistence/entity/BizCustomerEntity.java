package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_customer")
public class BizCustomerEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long projectId;
    private String customerNo;
    private String customerName;
    private String customerType;
    private String contactName;
    private String phoneMask;
    private String status;
    private LocalDateTime deletedAt;
}
