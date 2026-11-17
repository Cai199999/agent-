package com.agentgongdan.domain.chat;

import java.util.List;

/**
 * 对话流式编排指令。Python 无状态，历史与检索上下文均由 Java 传入。
 */
public record ChatStreamCommand(
        String traceId,
        long sessionId,
        long assistantMessageId,
        String model,
        List<ChatTurn> messages,
        List<ChatContextChunk> chunks,
        long actorId,
        List<String> roles
) {
}
