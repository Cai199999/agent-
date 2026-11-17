package com.agentgongdan.app.ports.ai;

import com.agentgongdan.domain.chat.ChatStreamCommand;

/**
 * 对话流端口：应用层不直接依赖 Python/LLM SDK 或 HTTP 细节。
 */
public interface ChatStreamPort {

    void stream(ChatStreamCommand command, ChatStreamHandler handler);
}
