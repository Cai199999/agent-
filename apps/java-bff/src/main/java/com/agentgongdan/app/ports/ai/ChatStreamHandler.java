package com.agentgongdan.app.ports.ai;

import java.util.Map;

/**
 * 上游 SSE 帧回调。事件名为 snake_case 协议名，数据已解析为 Map。
 */
public interface ChatStreamHandler {

    void onFrame(String event, Map<String, Object> data);

    void onError(String internalCode, String message);

    default boolean isCancelled() {
        return false;
    }
}
