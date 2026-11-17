package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("kb_doc")
public class KbDocEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String docNo;
    private String title;
    private String categoryCode;
    private String sourceType;
    private Long sourceRefId;
    private Long fileId;
    private String visibilityType;
    private String status;
    private Integer currentVersion;
    private Integer chunkCount;
    private String embeddingModel;
    private Integer embeddingDim;
    private String vectorCollection;
    private Integer indexVersion;
    private String checksum;
    private String errorMessage;
    private Integer version;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private LocalDateTime deletedAt;
    private Long deletedBy;
}
