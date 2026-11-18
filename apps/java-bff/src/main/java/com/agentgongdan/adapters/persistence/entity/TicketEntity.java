package com.agentgongdan.adapters.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ticket")
public class TicketEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long projectId;
    private Long buildingId;
    private String ticketNo;
    private String title;
    private String description;
    private String ticketTypeCode;
    private String priorityCode;
    private String statusCode;
    private String sourceCode;
    private String sourceRefType;
    private Long sourceRefId;
    private Long triageId;
    private Long reporterId;
    private Long reporterDeptId;
    private Long assigneeId;
    private Long assigneeDeptId;
    private Long customerId;
    private Long roomId;
    private Long equipmentId;
    private Long serviceItemId;
    private String serviceItemText;
    private Long workflowId;
    private String currentNodeCode;
    private LocalDateTime appointmentAt;
    private LocalDateTime receivedAt;
    private LocalDateTime arrivedAt;
    private String onsiteSituation;
    private LocalDateTime taskDueAt;
    private String contactName;
    private String contactPhoneEnc;
    private String contactPhoneMask;
    private String addressText;
    private String indoorPublicArea;
    private String locationText;
    private String locationType;
    private Long slaPolicyId;
    private LocalDateTime slaResponseDueAt;
    private LocalDateTime slaResolveDueAt;
    private Integer slaResponseBreached;
    private Integer slaResolveBreached;
    private LocalDateTime holdStartedAt;
    private String holdReason;
    private Integer holdMinutes;
    private LocalDateTime firstResponseAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime closedAt;
    private String closeReasonCode;
    private String cancelReason;
    private String incompleteReasonCode;
    private Integer satisfactionScore;
    private String satisfactionComment;
    private Long ratedBy;
    private LocalDateTime satisfactionAt;
    private Integer version;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private LocalDateTime deletedAt;
    private Long deletedBy;
}
