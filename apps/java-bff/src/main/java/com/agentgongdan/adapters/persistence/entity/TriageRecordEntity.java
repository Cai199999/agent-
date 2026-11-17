package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("triage_record")
public class TriageRecordEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long sessionId;
    private Long messageId;
    private String traceId;
    private String intentCode;
    private String entitiesJson;
    private BigDecimal confidence;
    private BigDecimal threshold;
    private String action;
    private Integer lowConfidence;
    private Integer clarifyRound;
    private String draftJson;
    private String draftStatus;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    private String model;
    private String promptVersion;
    private Integer latencyMs;
    private String correctedIntentCode;
    private Long correctedBy;
    private LocalDateTime correctedAt;
    private LocalDateTime createdAt;
    private Long createdBy;
}
