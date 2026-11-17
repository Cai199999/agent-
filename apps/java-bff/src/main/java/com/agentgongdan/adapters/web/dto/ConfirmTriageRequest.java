package com.agentgongdan.adapters.web.dto;

import jakarta.validation.constraints.Size;

/**
 * 确认分诊草稿时允许用户覆盖的字段；未提供的字段沿用草稿。
 */
public record ConfirmTriageRequest(
        @Size(max = 200, message = "标题不能超过 200 个字符") String title,
        @Size(max = 2000, message = "描述不能超过 2000 个字符") String description,
        @Size(max = 32) String ticketTypeCode,
        @Size(max = 16) String priorityCode,
        @Size(max = 255) String locationText,
        Long customerId,
        Long roomId,
        Long equipmentId
) {
}
