package com.agentgongdan.domain.chat;

/**
 * 送入模型的一轮对话。
 */
public record ChatTurn(String role, String content) {
}
