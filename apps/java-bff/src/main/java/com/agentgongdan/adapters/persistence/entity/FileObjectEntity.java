package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("file_object")
public class FileObjectEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String objectKey;
    private String bucket;
    private String originalName;
    private String contentType;
    private Long sizeBytes;
    private String checksumSha256;
    private String storageProvider;
    private String bizType;
    private Long bizId;
    private String status;
    private Long uploadedBy;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private LocalDateTime deletedAt;
    private Long deletedBy;
}
