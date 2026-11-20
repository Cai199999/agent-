package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.AgentTaskEntity;
import com.agentgongdan.adapters.persistence.mapper.AgentTaskMapper;
import com.agentgongdan.app.ports.agent.AgentTaskQueuePort;
import com.agentgongdan.app.ports.metrics.MetricsPort;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 启动恢复测试：PENDING 重投、超时 RUNNING 标记失败、开关关闭时不动作。
 */
@ExtendWith(MockitoExtension.class)
class AgentTaskRecoveryRunnerTest {

    @Mock
    private AgentTaskMapper taskMapper;
    @Mock
    private AgentTaskEventService eventService;
    @Mock
    private AgentTaskQueuePort taskQueue;
    @Mock
    private MetricsPort metrics;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AgentTaskEntity.class);
    }

    @Test
    @DisplayName("PENDING 任务启动后重新入队")
    void pendingTasksAreRequeued() {
        AgentTaskEntity pending = entity("task-pending", "PENDING");
        when(taskMapper.selectList(any())).thenReturn(List.of(pending));
        AgentTaskRecoveryRunner runner = runner(true);

        int requeued = runner.requeuePending();

        assertThat(requeued).isEqualTo(1);
        verify(taskQueue).enqueue("task-pending");
    }

    @Test
    @DisplayName("超时 RUNNING 任务标记 FAILED 并记录事件")
    void staleRunningTasksAreFailed() {
        AgentTaskEntity running = entity("task-running", "RUNNING");
        when(taskMapper.selectList(any())).thenReturn(List.of(running));
        doReturn(1).when(taskMapper).update(isNull(), any());
        AgentTaskRecoveryRunner runner = runner(true);

        int failed = runner.failStaleRunning();

        assertThat(failed).isEqualTo(1);
        verify(eventService).record("task-running", "FAILED",
                "执行实例重启或任务超时，任务已中断；请使用相同 clientTaskId 重试");
        verify(metrics).taskStatus("ticket.query", "FAILED");
    }

    @Test
    @DisplayName("恢复开关关闭：启动时不做任何数据库操作")
    void disabledRecoveryDoesNothing() {
        runner(false).recoverOnStartup();

        verifyNoInteractions(taskMapper, taskQueue);
    }

    private AgentTaskRecoveryRunner runner(boolean enabled) {
        return new AgentTaskRecoveryRunner(taskMapper, eventService, taskQueue, metrics,
                enabled, 300, 100);
    }

    private AgentTaskEntity entity(String taskId, String status) {
        AgentTaskEntity entity = new AgentTaskEntity();
        entity.setTaskId(taskId);
        entity.setCapability("ticket.query");
        entity.setStatus(status);
        entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        return entity;
    }
}
