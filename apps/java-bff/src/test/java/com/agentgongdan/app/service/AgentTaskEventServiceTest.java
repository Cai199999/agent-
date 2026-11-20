package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.AgentTaskEventEntity;
import com.agentgongdan.adapters.persistence.mapper.AgentTaskEventMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事件序号与类型测试：seq 任务内递增，type 由状态派生。
 */
@ExtendWith(MockitoExtension.class)
class AgentTaskEventServiceTest {

    @Mock
    private AgentTaskEventMapper eventMapper;

    private AgentTaskEventService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AgentTaskEventEntity.class);
        service = new AgentTaskEventService(eventMapper);
    }

    @Test
    @DisplayName("事件按任务内 seq 递增，并写入类型")
    void recordsSeqAndType() {
        AgentTaskEventEntity last = new AgentTaskEventEntity();
        last.setSeq(1L);
        when(eventMapper.selectOne(any())).thenReturn(null, last);

        service.record("task-1", "PENDING", null);
        service.record("task-1", "SUCCEEDED", null);

        ArgumentCaptor<AgentTaskEventEntity> captor =
                ArgumentCaptor.forClass(AgentTaskEventEntity.class);
        verify(eventMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues().get(0).getSeq()).isEqualTo(1L);
        assertThat(captor.getAllValues().get(0).getType()).isEqualTo("TASK_QUEUED");
        assertThat(captor.getAllValues().get(1).getSeq()).isEqualTo(2L);
        assertThat(captor.getAllValues().get(1).getType()).isEqualTo("TASK_SUCCEEDED");
    }

    @Test
    @DisplayName("显式类型优先于状态推导")
    void explicitTypeWins() {
        when(eventMapper.selectOne(any())).thenReturn(null);

        service.record("task-1", "TASK_CONFIRM_EXPIRED", "FAILED", "确认已过期");

        ArgumentCaptor<AgentTaskEventEntity> captor =
                ArgumentCaptor.forClass(AgentTaskEventEntity.class);
        verify(eventMapper).insert(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("TASK_CONFIRM_EXPIRED");
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("afterSeq 使用 seq 游标读取")
    void listAfterUsesSeqCursor() {
        service.listAfter("task-1", 3L);

        verify(eventMapper).selectList(any());
    }
}
