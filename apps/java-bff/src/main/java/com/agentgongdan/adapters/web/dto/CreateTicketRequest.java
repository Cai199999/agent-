package com.agentgongdan.adapters.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateTicketRequest(
        @NotBlank(message = "标题不能为空") String title,
        String description,
        @NotBlank(message = "工单类型不能为空") String ticketTypeCode,
        String priorityCode,
        String locationText,
        Long customerId,
        Long roomId,
        Long equipmentId,
        Long projectId,
        Long buildingId,
        Long serviceItemId,
        Long workflowId,
        String appointmentAt,
        String contactName,
        String addressText,
        String indoorPublicArea
) {
}
