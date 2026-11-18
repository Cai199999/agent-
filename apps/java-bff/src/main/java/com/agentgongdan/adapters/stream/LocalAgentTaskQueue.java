package com.agentgongdan.adapters.stream;

import com.agentgongdan.app.ports.agent.AgentTaskJobHandler;
import com.agentgongdan.app.ports.agent.AgentTaskQueuePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * MVP 进程内队列适配器。
 * 升级点：替换为 Redis Stream / RabbitMQ / Kafka 实现同名端口；
 * 任务状态仍以 agent_task 为准，消费者按 taskId 幂等领取。
 */
@Component
public class LocalAgentTaskQueue implements AgentTaskQueuePort {

    private static final Logger log = LoggerFactory.getLogger(LocalAgentTaskQueue.class);

    private final ThreadPoolTaskExecutor executor;
    private final AgentTaskJobHandler jobHandler;

    public LocalAgentTaskQueue(@Qualifier("agentTaskExecutor") ThreadPoolTaskExecutor executor,
                               AgentTaskJobHandler jobHandler) {
        this.executor = executor;
        this.jobHandler = jobHandler;
    }

    @Override
    public void enqueue(String taskId) {
        executor.execute(() -> {
            try {
                jobHandler.execute(taskId);
            } catch (Exception e) {
                log.error("agent task {} execution error", taskId, e);
            }
        });
    }
}
