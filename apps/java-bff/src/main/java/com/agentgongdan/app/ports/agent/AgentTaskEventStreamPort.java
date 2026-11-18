package com.agentgongdan.app.ports.agent;

import com.agentgongdan.app.dto.AgentTaskEvent;

import java.util.function.Consumer;

/**
 * 任务事件订阅端口：屏蔽 SSE / WebSocket / MQ 的具体传输实现。
 */
public interface AgentTaskEventStreamPort {

    Subscription watch(String taskId,
                       long actorId,
                       String afterEventId,
                       Consumer<AgentTaskEvent> onEvent,
                       Runnable onHeartbeat,
                       Runnable onComplete,
                       Consumer<Exception> onError);

    interface Subscription {
        void close();
    }
}
