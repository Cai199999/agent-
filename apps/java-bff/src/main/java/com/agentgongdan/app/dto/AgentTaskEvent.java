package com.agentgongdan.app.dto;

/**
 * 应用层任务事件，不绑定 HTTP/SSE 传输格式。
 */
public record AgentTaskEvent(
        String id,
        String taskId,
        String status,
        String message,
        String createdAt
) {
}
