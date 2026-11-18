package com.agentgongdan.app.ports.agent;

/**
 * 异步任务执行入口；由队列适配器调用，避免应用层依赖具体 MQ/线程池。
 */
public interface AgentTaskJobHandler {

    void execute(String taskId);
}
