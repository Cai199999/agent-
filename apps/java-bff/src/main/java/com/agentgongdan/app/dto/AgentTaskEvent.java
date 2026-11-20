package com.agentgongdan.app.dto;

/**
 * 应用层任务事件，不绑定 HTTP/SSE 传输格式。
 * seq 为任务内单调递增序号，作为轮询/断线重连游标。
 */
public record AgentTaskEvent(
        String id,
        long seq,
        String taskId,
        String type,
        String status,
        String message,
        String createdAt
) {
}
