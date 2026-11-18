package com.agentgongdan.adapters.web.dto;

/**
 * Python/内部调用导出请求；时间使用 ISO 8601（带时区）。
 */
public record InternalTicketExportRequest(
        String startTime,
        String endTime,
        String status,
        String keyword,
        String ticketTypeCode,
        String priorityCode,
        String location,
        Long assigneeId
) {
}
