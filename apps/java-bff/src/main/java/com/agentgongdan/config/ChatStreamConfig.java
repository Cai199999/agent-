package com.agentgongdan.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 对话流执行器：与 Servlet 线程隔离；池大小/队列可配置，为后续水平扩展预留。
 */
@Configuration
public class ChatStreamConfig {

    @Bean(name = "chatStreamExecutor")
    public ThreadPoolTaskExecutor chatStreamExecutor(
            @Value("${CHAT_STREAM_POOL_SIZE:8}") int poolSize,
            @Value("${CHAT_STREAM_QUEUE_CAPACITY:64}") int queueCapacity,
            @Value("${CHAT_STREAM_KEEP_ALIVE_SECONDS:120}") int keepAliveSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("chat-stream-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
