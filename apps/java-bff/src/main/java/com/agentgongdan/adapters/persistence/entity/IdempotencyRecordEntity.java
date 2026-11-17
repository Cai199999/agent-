package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("idempotency_record")
public class IdempotencyRecordEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String idemKey;
    private String scope;
    private String requestHash;
    private String status;
    private Integer responseCode;
    private String responseJson;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
