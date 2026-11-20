package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.AgentTaskEntity;
import com.agentgongdan.adapters.persistence.mapper.AgentTaskMapper;
import com.agentgongdan.adapters.web.dto.AgentTaskRequest;
import com.agentgongdan.adapters.web.dto.AgentTaskView;
import com.agentgongdan.app.ports.agent.AgentTaskQueuePort;
import com.agentgongdan.app.ports.metrics.MetricsPort;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 子 Agent Task API 契约测试（不依赖 MySQL / Python）。
 * 覆盖 Manifest 契约、确认令牌语义、幂等、scope、actor 隔离与状态机。
 */
@ExtendWith(MockitoExtension.class)
class AgentTaskServiceTest {

    private static final long ACTOR = 10001L;
    private static final long OTHER_ACTOR = 10002L;
    private static final List<String> ADMIN_ROLES = List.of("ADMIN");

    @Mock
    private AgentTaskMapper taskMapper;
    @Mock
    private AgentTaskEventService eventService;
    @Mock
    private AgentTaskWorker taskWorker;
    @Mock
    private AgentTaskQueuePort taskQueue;
    @Mock
    private MetricsPort metrics;

    private AgentTaskService service;

    @BeforeEach
    void setUp() {
        // 纯单元测试没有 MyBatis 启动流程，手工注册实体表信息以支持 LambdaWrapper。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AgentTaskEntity.class);
        service = new AgentTaskService(taskMapper, eventService, taskWorker, taskQueue,
                new ObjectMapper(), metrics, false, true,
                "https://agent.example.com/agent/v1", 65536);
    }

    @Test
    @DisplayName("Manifest 契约：必需字段、唯一能力、写能力必须确认")
    void manifestContract() {
        Map<String, Object> manifest = service.manifest();

        assertThat(manifest).containsKeys("agentId", "name", "version", "protocol",
                "taskEndpoint", "eventStreamEndpoint", "executionModes", "auth", "capabilities",
                "contextSchema", "limits", "observability", "downstream", "enforcement",
                "features", "endpoints", "envelope", "healthEndpoint", "readyEndpoint",
                "delegationEndpoint", "asyncDurability");
        assertThat(manifest.get("version")).isEqualTo("0.8.0");
        assertThat(manifest.get("protocol")).isEqualTo("agent-task-v1");
        assertThat(manifest.get("executionModes")).isEqualTo(List.of("SYNC", "ASYNC"));
        assertThat(manifest.get("healthEndpoint")).isEqualTo("/agent/v1/health");
        assertThat(manifest.get("readyEndpoint")).isEqualTo("/agent/v1/ready");
        assertThat(manifest.get("delegationEndpoint")).isEqualTo("/agent/v1/delegation/token");
        assertThat(manifest.get("observability")).isEqualTo(Map.of(
                "metricsEndpoint", "/agent/v1/metrics/summary",
                "actuatorMetrics", "/actuator/metrics"));
        assertThat(manifest.get("downstream")).isEqualTo(Map.of(
                "enabled", false,
                "protocol", "agent-task-v1"));
        assertThat(manifest.get("limits")).isEqualTo(Map.of(
                "maxContextRefs", 50,
                "maxRefIdLength", 64,
                "maxInputBytes", 65536,
                "maxCancelReasonLength", 200));
        assertThat(manifest.get("enforcement")).isEqualTo(Map.of(
                "deadlineAt", Map.of("enforced", true, "mode", "checked-before-execution"),
                "budget", Map.of("enforced", false, "mode", "recorded-only"),
                "runtimeTimeout", Map.of("enforced", false)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> capabilities = (List<Map<String, Object>>) manifest.get("capabilities");
        assertThat(capabilities).isNotEmpty();
        Set<String> names = capabilities.stream()
                .map(item -> String.valueOf(item.get("name")))
                .collect(Collectors.toSet());
        assertThat(names).hasSize(capabilities.size());
        assertThat(names).contains("ticket.query", "ticket.detail", "ticket.similar",
                "knowledge.search", "ticket.create", "ticket.export", "ticket.assign",
                "ticket.resolve", "ticket.verify", "ticket.close", "ticket.cancel",
                "ticket.hold", "ticket.resume", "ticket.finish", "ticket.comment.add",
                "ticket.material.add", "ticket.visit.add", "ticket.accept");

        for (Map<String, Object> capability : capabilities) {
            assertThat(capability).containsKeys("capabilityId", "name", "description",
                    "readOnly", "requiresConfirmation", "requiresConfirm", "executionModes",
                    "scopes", "permission", "mode", "inputSchema", "outputSchema");
            assertThat(String.valueOf(capability.get("capabilityId")))
                    .startsWith("property.");
            assertThat(String.valueOf(capability.get("mode"))).isIn("READ_ONLY", "WRITE");
            assertThat(capability.get("executionModes")).isEqualTo(List.of("SYNC", "ASYNC"));
            assertThat(capability.get("readOnly")).isEqualTo("READ_ONLY".equals(capability.get("mode")));
            assertThat(capability.get("requiresConfirmation"))
                    .isEqualTo(capability.get("requiresConfirm"));
            if ("WRITE".equals(capability.get("mode"))) {
                assertThat(capability.get("requiresConfirm")).isEqualTo(true);
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> auth = (Map<String, Object>) manifest.get("auth");
        assertThat(auth.get("header")).isEqualTo("X-Internal-Token");
        assertThat(auth.get("delegationHeader")).isEqualTo("X-Delegation-Token");
    }

    @Test
    @DisplayName("Agent Card 与 Manifest 版本/能力一致，且不暴露密钥")
    void agentCardContract() {
        Map<String, Object> manifest = service.manifest();
        Map<String, Object> card = service.agentCard();

        assertThat(card.get("version")).isEqualTo(manifest.get("version"));
        assertThat(card.get("url")).isEqualTo("https://agent.example.com/agent/v1");
        assertThat(card.get("protocolVersion")).isEqualTo(manifest.get("protocol"));
        assertThat(card.get("skills")).isEqualTo(manifest.get("capabilities"));
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = (Map<String, Object>) card.get("capabilities");
        assertThat(capabilities.get("streaming")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> schemes = (Map<String, Object>) card.get("securitySchemes");
        assertThat(schemes).containsKeys("internalToken", "delegationToken");
        assertThat(card.toString()).doesNotContain("secret", "token=");
    }

    @Test
    @DisplayName("只读 SYNC：立即执行并落库")
    void readSyncExecutesInline() {
        doAnswer(invocation -> {
            AgentTaskEntity entity = invocation.getArgument(0);
            entity.setStatus("SUCCEEDED");
            return null;
        }).when(taskWorker).executeInline(any(), anyList());

        AgentTaskView view = service.submit(readRequest(null, null), ACTOR, ADMIN_ROLES,
                null, null, "trace-1");

        assertThat(view.status()).isEqualTo("SUCCEEDED");
        assertThat(view.executionMode()).isEqualTo("SYNC");
        assertThat(view.traceId()).isEqualTo("trace-1");
        assertThat(view.agentId()).isEqualTo("property-maintenance");
        assertThat(view.capabilityId()).isEqualTo("property.ticket.query");
        assertThat(view.progress()).isEqualTo(100);
        assertThat(view.currentStep()).isEqualTo("DONE");
        assertThat(view.usage()).containsEntry("source", "not-measured");
        verify(taskMapper).insert(any(AgentTaskEntity.class));
        verify(eventService).record(view.taskId(), "SUCCEEDED", null);
    }

    @Test
    @DisplayName("上下文参数：父任务/关联 ID/引用/预算/截止时间可持久化并回显")
    void contextParametersPersisted() {
        doAnswer(invocation -> {
            AgentTaskEntity entity = invocation.getArgument(0);
            entity.setStatus("SUCCEEDED");
            return null;
        }).when(taskWorker).executeInline(any(), anyList());
        AgentTaskRequest request = new AgentTaskRequest("ticket.query", Map.of("limit", 1),
                Map.of("ticketId", "1001"), null, null,
                "parent-task-1", "correlation-1", "2030-01-01T00:00:00+08:00",
                Map.of("maxTokens", 100));

        AgentTaskView view = service.submit(request, ACTOR, ADMIN_ROLES, null, null, "trace-5");

        ArgumentCaptor<AgentTaskEntity> captor = ArgumentCaptor.forClass(AgentTaskEntity.class);
        verify(taskMapper).insert(captor.capture());
        AgentTaskEntity saved = captor.getValue();
        assertThat(saved.getParentTaskId()).isEqualTo("parent-task-1");
        assertThat(saved.getCorrelationId()).isEqualTo("correlation-1");
        assertThat(saved.getContextRefsJson()).contains("ticketId");
        assertThat(saved.getBudgetJson()).contains("maxTokens");
        assertThat(saved.getDeadlineAt()).isNotNull();
        assertThat(view.parentTaskId()).isEqualTo("parent-task-1");
        assertThat(view.correlationId()).isEqualTo("correlation-1");
        assertThat(view.contextRefs()).containsEntry("ticketId", "1001");
        assertThat(view.budget()).containsEntry("maxTokens", 100);
        assertThat(view.deadlineAt()).isNotBlank();
        verify(metrics).taskStatus("ticket.query", "SUCCEEDED");
    }

    @Test
    @DisplayName("非法 deadlineAt 被拒绝")
    void invalidDeadlineRejected() {
        AgentTaskRequest request = new AgentTaskRequest("ticket.query", Map.of(), null,
                null, null, null, null, "not-a-time", null);

        assertThatThrownBy(() -> service.submit(request, ACTOR, ADMIN_ROLES, null, null, "t"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(taskMapper);
    }

    @Test
    @DisplayName("已过期 deadline 在提交时被拒绝，不落任务")
    void expiredDeadlineRejected() {
        AgentTaskRequest request = new AgentTaskRequest("ticket.query", Map.of(), null,
                null, null, null, null, "2020-01-01T00:00:00Z", null);

        assertThatThrownBy(() -> service.submit(request, ACTOR, ADMIN_ROLES, null, null, "t"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(taskMapper);
    }

    @Test
    @DisplayName("input 超过配置上限被拒绝")
    void oversizedInputRejected() {
        AgentTaskRequest request = new AgentTaskRequest("ticket.query",
                Map.of("keyword", "x".repeat(70000)), null,
                null, null, null, null, null, null);

        assertThatThrownBy(() -> service.submit(request, ACTOR, ADMIN_ROLES, null, null, "t"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(taskMapper);
    }

    @Test
    @DisplayName("只读 ASYNC：入队 PENDING，由 Worker 异步执行")
    void readAsyncEnqueues() {
        AgentTaskView view = service.submit(readRequest("ASYNC", null), ACTOR, ADMIN_ROLES,
                null, null, "trace-2");

        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.executionMode()).isEqualTo("ASYNC");
        verifyNoInteractions(taskWorker);
        verify(taskMapper).insert(any(AgentTaskEntity.class));
        verify(taskQueue).enqueue(view.taskId());
    }

    @Test
    @DisplayName("写能力：先 WAITING_CONFIRM，不执行、不入队")
    void writeWaitsForConfirmation() {
        AgentTaskView view = service.submit(writeRequest("SYNC", null), ACTOR, ADMIN_ROLES,
                null, null, "trace-3");

        assertThat(view.status()).isEqualTo("WAITING_CONFIRM");
        assertThat(view.confirmationToken()).isNotBlank();
        assertThat(view.confirmationExpiresAt()).isNotBlank();
        verifyNoInteractions(taskWorker);
        verifyNoInteractions(taskQueue);
        verify(eventService).record(view.taskId(), "WAITING_CONFIRM", null);
    }

    @Test
    @DisplayName("幂等：相同 Actor + clientTaskId 返回同一任务且不重复执行")
    void clientTaskIdIsIdempotent() {
        AgentTaskEntity existing = new AgentTaskEntity();
        existing.setTaskId("existing-task");
        existing.setAgentId("property-maintenance");
        existing.setCapability("ticket.query");
        existing.setStatus("SUCCEEDED");
        existing.setActorId(ACTOR);
        when(taskMapper.selectOne(any())).thenReturn(existing);

        AgentTaskView view = service.submit(readRequest(null, "client-1"), ACTOR, ADMIN_ROLES,
                null, null, "trace-4");

        assertThat(view.taskId()).isEqualTo("existing-task");
        verify(taskMapper, never()).insert(any(AgentTaskEntity.class));
        verifyNoInteractions(taskWorker);
    }

    @Test
    @DisplayName("非法 executionMode 被拒绝")
    void invalidExecutionModeRejected() {
        AgentTaskRequest request = readRequest("BATCH", null);

        assertThatThrownBy(() -> service.submit(request, ACTOR, ADMIN_ROLES, null, null, "t"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(taskMapper);
    }

    @Test
    @DisplayName("委托 scope 不足时拒绝，不落任务")
    void delegationScopeDenied() {
        assertThatThrownBy(() -> service.submit(writeRequest("SYNC", null), ACTOR, ADMIN_ROLES,
                List.of("ticket.query"), "delegation-1", "t"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_FORBIDDEN);
        verifyNoInteractions(taskMapper);
    }

    @Test
    @DisplayName("委托 scope 通配符允许")
    void delegationWildcardAllowed() {
        AgentTaskView view = service.submit(writeRequest("SYNC", null), ACTOR, ADMIN_ROLES,
                List.of("*"), "delegation-2", "t");

        assertThat(view.status()).isEqualTo("WAITING_CONFIRM");
        assertThat(view.delegationId()).isEqualTo("delegation-2");
    }

    @Test
    @DisplayName("确认：非任务所有者不可查看/确认")
    void confirmRequiresOwner() {
        AgentTaskEntity entity = confirmedEntity("token-1");
        when(taskMapper.selectOne(any())).thenReturn(entity);

        assertThatThrownBy(() -> service.confirm(entity.getTaskId(), "token-1", OTHER_ACTOR,
                ADMIN_ROLES))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_FORBIDDEN);

        assertThatThrownBy(() -> service.get(entity.getTaskId(), OTHER_ACTOR))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_FORBIDDEN);
    }

    @Test
    @DisplayName("确认：令牌错误拒绝，任务保持 WAITING_CONFIRM")
    void confirmRejectsWrongToken() {
        AgentTaskEntity entity = confirmedEntity("token-1");
        when(taskMapper.selectOne(any())).thenReturn(entity);

        assertThatThrownBy(() -> service.confirm(entity.getTaskId(), "wrong", ACTOR, ADMIN_ROLES))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_FORBIDDEN);
        assertThat(entity.getStatus()).isEqualTo("WAITING_CONFIRM");
        assertThat(entity.getConfirmationToken()).isEqualTo("token-1");
    }

    @Test
    @DisplayName("确认：过期令牌置 FAILED 并清理令牌")
    void confirmExpiredTokenFails() {
        AgentTaskEntity entity = confirmedEntity("token-1");
        entity.setConfirmationExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(taskMapper.selectOne(any())).thenReturn(entity);

        assertThatThrownBy(() -> service.confirm(entity.getTaskId(), "token-1", ACTOR, ADMIN_ROLES))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED);
        assertThat(entity.getStatus()).isEqualTo("FAILED");
        assertThat(entity.getConfirmationToken()).isNull();
        verify(eventService).record(entity.getTaskId(), "TASK_CONFIRM_EXPIRED",
                "FAILED", "确认已过期");
    }

    @Test
    @DisplayName("确认：成功一次后令牌清理，重复确认冲突")
    void confirmIsSingleUse() {
        AgentTaskEntity entity = confirmedEntity("token-1");
        when(taskMapper.selectOne(any())).thenReturn(entity);
        doAnswer(invocation -> {
            AgentTaskEntity arg = invocation.getArgument(0);
            arg.setStatus("SUCCEEDED");
            return null;
        }).when(taskWorker).executeInline(any(), anyList());

        AgentTaskView view = service.confirm(entity.getTaskId(), "token-1", ACTOR, ADMIN_ROLES);

        assertThat(view.status()).isEqualTo("SUCCEEDED");
        assertThat(view.confirmationToken()).isNull();
        assertThat(entity.getConfirmedAt()).isNotNull();
        verify(taskMapper).updateById(entity);

        assertThatThrownBy(() -> service.confirm(entity.getTaskId(), "token-1", ACTOR, ADMIN_ROLES))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.TICKET_STATE_CONFLICT);
    }

    @Test
    @DisplayName("确认：异步任务确认后进入队列而非同步执行")
    void asyncConfirmEnqueues() {
        AgentTaskEntity entity = confirmedEntity("token-1");
        entity.setExecutionMode("ASYNC");
        when(taskMapper.selectOne(any())).thenReturn(entity);

        AgentTaskView view = service.confirm(entity.getTaskId(), "token-1", ACTOR, ADMIN_ROLES);

        assertThat(view.status()).isEqualTo("PENDING");
        verifyNoInteractions(taskWorker);
        verify(taskQueue).enqueue(entity.getTaskId());
        verify(eventService).record(entity.getTaskId(), "PENDING", "已确认，任务进入队列");
    }

    @Test
    @DisplayName("确认：REJECT 进入 REJECTED 且不执行业务")
    void confirmRejectDoesNotExecute() {
        AgentTaskEntity entity = confirmedEntity("token-1");
        when(taskMapper.selectOne(any())).thenReturn(entity);

        AgentTaskView view = service.confirm(entity.getTaskId(), "token-1", "REJECT",
                ACTOR, ADMIN_ROLES);

        assertThat(view.status()).isEqualTo("REJECTED");
        assertThat(view.errorMessage()).isEqualTo("调用方拒绝执行");
        assertThat(view.confirmationToken()).isNull();
        assertThat(view.progress()).isEqualTo(100);
        assertThat(view.currentStep()).isEqualTo("REJECTED");
        verifyNoInteractions(taskWorker, taskQueue);
        verify(eventService).record(entity.getTaskId(), "REJECTED", "调用方拒绝执行");
        verify(metrics).taskStatus("ticket.create", "REJECTED");
    }

    @Test
    @DisplayName("确认：非法 decision 拒绝且任务保持 WAITING_CONFIRM")
    void confirmRejectsInvalidDecision() {
        AgentTaskEntity entity = confirmedEntity("token-1");
        when(taskMapper.selectOne(any())).thenReturn(entity);

        assertThatThrownBy(() -> service.confirm(entity.getTaskId(), "token-1", "MAYBE",
                ACTOR, ADMIN_ROLES))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(entity.getStatus()).isEqualTo("WAITING_CONFIRM");
    }

    @Test
    @DisplayName("按 clientTaskId 恢复查询：命中自己的任务")
    void findByClientTaskId() {
        AgentTaskEntity existing = new AgentTaskEntity();
        existing.setTaskId("existing-task");
        existing.setAgentId("property-maintenance");
        existing.setCapability("ticket.query");
        existing.setStatus("SUCCEEDED");
        existing.setActorId(ACTOR);
        existing.setClientTaskId("client-1");
        when(taskMapper.selectOne(any())).thenReturn(existing);

        AgentTaskView view = service.findByClientTaskId("client-1", ACTOR);

        assertThat(view.taskId()).isEqualTo("existing-task");
        assertThat(view.clientTaskId()).isEqualTo("client-1");
        assertThat(view.capabilityId()).isEqualTo("property.ticket.query");
    }

    @Test
    @DisplayName("按 clientTaskId 恢复查询：未命中返回 20001")
    void findByClientTaskIdNotFound() {
        when(taskMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.findByClientTaskId("missing", ACTOR))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.TICKET_NOT_FOUND);
    }

    @Test
    @DisplayName("取消：记录取消原因并回显")
    void cancelRecordsReason() {
        AgentTaskEntity pending = confirmedEntity("token-1");
        AgentTaskEntity cancelled = confirmedEntity(null);
        cancelled.setStatus("CANCELLED");
        cancelled.setCancelReason("主 Agent 超时");
        when(taskMapper.selectOne(any())).thenReturn(pending, cancelled);
        doReturn(1).when(taskMapper).update(isNull(), any());

        AgentTaskView view = service.cancel(pending.getTaskId(), "主 Agent 超时", ACTOR);

        assertThat(view.status()).isEqualTo("CANCELLED");
        assertThat(view.cancelReason()).isEqualTo("主 Agent 超时");
        verify(eventService).record(pending.getTaskId(), "CANCELLED", "调用方已取消");
    }

    @Test
    @DisplayName("取消：原因超长被拒绝")
    void cancelReasonTooLongRejected() {
        AgentTaskEntity pending = confirmedEntity("token-1");
        when(taskMapper.selectOne(any())).thenReturn(pending);

        assertThatThrownBy(() -> service.cancel(pending.getTaskId(), "x".repeat(201), ACTOR))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(taskQueue);
    }

    @Test
    @DisplayName("取消：终态任务不可取消")
    void cannotCancelTerminalTask() {
        AgentTaskEntity entity = new AgentTaskEntity();
        entity.setTaskId("task-terminal");
        entity.setActorId(ACTOR);
        entity.setStatus("SUCCEEDED");
        when(taskMapper.selectOne(any())).thenReturn(entity);

        assertThatThrownBy(() -> service.cancel("task-terminal", ACTOR))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.TICKET_STATE_CONFLICT);
    }

    @Test
    @DisplayName("取消：WAITING_CONFIRM 可取消并清理令牌")
    void cancelWaitingConfirm() {
        AgentTaskEntity pending = confirmedEntity("token-1");
        AgentTaskEntity cancelled = confirmedEntity(null);
        cancelled.setStatus("CANCELLED");
        when(taskMapper.selectOne(any())).thenReturn(pending, cancelled);
        doReturn(1).when(taskMapper).update(isNull(), any());

        AgentTaskView view = service.cancel(pending.getTaskId(), ACTOR);

        assertThat(view.status()).isEqualTo("CANCELLED");
        verify(eventService).record(pending.getTaskId(), "CANCELLED", "调用方已取消");
    }

    private AgentTaskRequest readRequest(String mode, String clientTaskId) {
        return new AgentTaskRequest("ticket.query", Map.of("limit", 1), null,
                clientTaskId, mode, null, null, null, null);
    }

    private AgentTaskRequest writeRequest(String mode, String clientTaskId) {
        return new AgentTaskRequest("ticket.create", Map.of("ticketTypeCode", "ELEVATOR"),
                null, clientTaskId, mode, null, null, null, null);
    }

    private AgentTaskEntity confirmedEntity(String token) {
        AgentTaskEntity entity = new AgentTaskEntity();
        entity.setTaskId("task-1");
        entity.setAgentId("property-maintenance");
        entity.setCapability("ticket.create");
        entity.setStatus("WAITING_CONFIRM");
        entity.setActorId(ACTOR);
        entity.setActorRoles("ADMIN");
        entity.setExecutionMode("SYNC");
        entity.setConfirmationToken(token);
        entity.setConfirmationExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        return entity;
    }
}
