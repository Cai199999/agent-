package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("vector_object")
public class VectorObjectEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String objectType;
    private Long objectId;
    private Integer objectVersion;
    private Integer seqNo;
    private String vectorId;
    private String vectorCollection;
    private String contentHash;
    private String status;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private LocalDateTime deletedAt;
    private Long deletedBy;
}
