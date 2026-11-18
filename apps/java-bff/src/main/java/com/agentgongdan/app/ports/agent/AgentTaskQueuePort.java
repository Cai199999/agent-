package com.agentgongdan.app.ports.agent;

/**
 * 异步任务队列端口。
 * MVP 为进程内线程池；未来可替换为 Redis Stream / MQ，无需改应用服务。
 */
public interface AgentTaskQueuePort {

    void enqueue(String taskId);
}
