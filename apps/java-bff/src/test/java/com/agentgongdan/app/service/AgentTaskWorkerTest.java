package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.AgentTaskEntity;
import com.agentgongdan.adapters.persistence.mapper.AgentTaskMapper;
import com.agentgongdan.app.ports.metrics.MetricsPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Worker 轻量生产适配测试：截止时间在执行前生效。
 */
@ExtendWith(MockitoExtension.class)
class AgentTaskWorkerTest {

    @Mock
    private AgentTaskMapper taskMapper;
    @Mock
    private AgentTaskEventService eventService;
    @Mock
    private AgentTaskDispatcher dispatcher;
    @Mock
    private MetricsPort metrics;

    private AgentTaskWorker worker;

    @BeforeEach
    void setUp() {
        worker = new AgentTaskWorker(taskMapper, eventService, dispatcher,
                new ObjectMapper(), metrics, true);
    }

    @Test
    @DisplayName("执行前发现 deadline 已过：FAILED/20005，不调用业务能力")
    void expiredDeadlineFailsBeforeDispatch() {
        AgentTaskEntity entity = entity(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));

        worker.executeInline(entity, List.of("ADMIN"));

        assertThat(entity.getStatus()).isEqualTo("FAILED");
        assertThat(entity.getErrorCode()).isEqualTo("20005");
        assertThat(entity.getErrorMessage()).contains("截止时间");
        assertThat(entity.getLatencyMs()).isZero();
        verifyNoInteractions(dispatcher);
    }

    @Test
    @DisplayName("deadline 未到：正常执行并成功")
    void futureDeadlineExecutes() {
        AgentTaskEntity entity = entity(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        when(dispatcher.dispatch(anyString(), anyMap(), anyLong(), anyList(), anyString()))
                .thenReturn(Map.of("ok", true));

        worker.executeInline(entity, List.of("ADMIN"));

        assertThat(entity.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(entity.getErrorCode()).isNull();
        verify(dispatcher).dispatch(anyString(), anyMap(), anyLong(), anyList(), anyString());
    }

    private AgentTaskEntity entity(LocalDateTime deadline) {
        AgentTaskEntity entity = new AgentTaskEntity();
        entity.setTaskId("task-deadline");
        entity.setCapability("ticket.query");
        entity.setStatus("RUNNING");
        entity.setActorId(10001L);
        entity.setActorRoles("ADMIN");
        entity.setInputJson("{}");
        entity.setDeadlineAt(deadline);
        return entity;
    }
}
