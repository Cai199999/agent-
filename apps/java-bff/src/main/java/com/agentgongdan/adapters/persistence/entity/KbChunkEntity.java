package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("kb_chunk")
public class KbChunkEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long docId;
    private Integer docVersion;
    private Integer chunkNo;
    private String content;
    private Integer tokenCount;
    private String vectorId;
    private String vectorCollection;
    private String metadataJson;
    private String status;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private LocalDateTime deletedAt;
    private Long deletedBy;
}
