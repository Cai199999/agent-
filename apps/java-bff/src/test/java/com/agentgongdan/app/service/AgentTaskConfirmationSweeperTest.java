package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.AgentTaskEntity;
import com.agentgongdan.adapters.persistence.mapper.AgentTaskMapper;
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
 * 确认过期 sweeper：只处理真正过期的 WAITING_CONFIRM，并保证并发安全。
 */
@ExtendWith(MockitoExtension.class)
class AgentTaskConfirmationSweeperTest {

    @Mock
    private AgentTaskMapper taskMapper;
    @Mock
    private AgentTaskEventService eventService;
    @Mock
    private MetricsPort metrics;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AgentTaskEntity.class);
    }

    @Test
    @DisplayName("过期确认置 FAILED、清空令牌并记录事件")
    void expiresOverdueConfirmation() {
        AgentTaskEntity entity = new AgentTaskEntity();
        entity.setTaskId("task-expired");
        entity.setCapability("ticket.create");
        entity.setStatus("WAITING_CONFIRM");
        entity.setConfirmationExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(taskMapper.selectList(any())).thenReturn(List.of(entity));
        doReturn(1).when(taskMapper).update(isNull(), any());
        AgentTaskConfirmationSweeper sweeper =
                new AgentTaskConfirmationSweeper(taskMapper, eventService, metrics, 100);

        int expired = sweeper.expireOverdue();

        assertThat(expired).isEqualTo(1);
        verify(eventService).record("task-expired", "TASK_CONFIRM_EXPIRED", "FAILED", "确认已过期");
        verify(metrics).taskStatus("ticket.create", "FAILED");
    }

    @Test
    @DisplayName("条件更新未命中（已被确认/取消）时不记录事件")
    void skipsWhenUpdateLosesRace() {
        AgentTaskEntity entity = new AgentTaskEntity();
        entity.setTaskId("task-raced");
        entity.setCapability("ticket.create");
        entity.setStatus("WAITING_CONFIRM");
        entity.setConfirmationExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(taskMapper.selectList(any())).thenReturn(List.of(entity));
        doReturn(0).when(taskMapper).update(isNull(), any());
        AgentTaskConfirmationSweeper sweeper =
                new AgentTaskConfirmationSweeper(taskMapper, eventService, metrics, 100);

        int expired = sweeper.expireOverdue();

        assertThat(expired).isZero();
        verifyNoInteractions(eventService, metrics);
    }
}
