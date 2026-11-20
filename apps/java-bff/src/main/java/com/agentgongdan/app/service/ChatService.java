package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.ChatEventEntity;
import com.agentgongdan.adapters.persistence.entity.ChatMessageCitationEntity;
import com.agentgongdan.adapters.persistence.entity.ChatMessageEntity;
import com.agentgongdan.adapters.persistence.entity.ChatSessionEntity;
import com.agentgongdan.adapters.persistence.entity.ChatToolCallEntity;
import com.agentgongdan.adapters.persistence.entity.KbDocEntity;
import com.agentgongdan.adapters.persistence.mapper.BizSequenceMapper;
import com.agentgongdan.adapters.persistence.mapper.ChatEventMapper;
import com.agentgongdan.adapters.persistence.mapper.ChatMessageCitationMapper;
import com.agentgongdan.adapters.persistence.mapper.ChatMessageMapper;
import com.agentgongdan.adapters.persistence.mapper.ChatSessionMapper;
import com.agentgongdan.adapters.persistence.mapper.KbDocMapper;
import com.agentgongdan.adapters.web.dto.ChatCitationView;
import com.agentgongdan.adapters.web.dto.ChatMessageView;
import com.agentgongdan.adapters.web.dto.ChatSessionView;
import com.agentgongdan.adapters.web.dto.ChatToolCallView;
import com.agentgongdan.adapters.web.dto.CreateChatSessionRequest;
import com.agentgongdan.adapters.web.dto.KnowledgeHitView;
import com.agentgongdan.adapters.web.dto.KnowledgeSearchRequest;
import com.agentgongdan.adapters.web.dto.PageResult;
import com.agentgongdan.adapters.web.dto.SendChatMessageRequest;
import com.agentgongdan.adapters.web.dto.TriageRecordView;
import com.agentgongdan.app.ports.ai.ChatStreamHandler;
import com.agentgongdan.app.ports.ai.ChatStreamPort;
import com.agentgongdan.app.ports.ai.TriagePort;
import com.agentgongdan.app.ports.audit.AuditPort;
import com.agentgongdan.app.ports.metrics.MetricsPort;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.agentgongdan.domain.audit.AuditEvent;
import com.agentgongdan.domain.chat.ChatContextChunk;
import com.agentgongdan.domain.chat.ChatStreamCommand;
import com.agentgongdan.domain.chat.ChatTurn;
import com.agentgongdan.domain.triage.TriageResult;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * AI 会话服务：Java 负责会话/消息落库、知识召回与 SSE 适配；Python 无状态生成。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final long TENANT = 0L;

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatMessageCitationMapper citationMapper;
    private final ChatEventMapper chatEventMapper;
    private final KbDocMapper kbDocMapper;
    private final BizSequenceMapper sequenceMapper;
    private final KnowledgeService knowledgeService;
    private final ChatStreamPort chatStreamPort;
    private final TriagePort triagePort;
    private final TriageService triageService;
    private final RuleTriageService ruleTriageService;
    private final ChatToolCallService toolCallService;
    private final TaskExecutor chatStreamExecutor;
    private final AuditPort auditPort;
    private final MetricsPort metrics;
    private final ObjectMapper objectMapper;
    private final int historyLimit;
    private final int contextTopK;
    private final double contextScoreThreshold;
    private final double contextRelativeScoreRatio;
    private final int contextMaxChars;
    private final long streamTimeoutSeconds;
    private final String defaultModel;

    public ChatService(ChatSessionMapper sessionMapper,
                       ChatMessageMapper messageMapper,
                       ChatMessageCitationMapper citationMapper,
                       ChatEventMapper chatEventMapper,
                       KbDocMapper kbDocMapper,
                       BizSequenceMapper sequenceMapper,
                       KnowledgeService knowledgeService,
                       ChatStreamPort chatStreamPort,
                       TriagePort triagePort,
                       TriageService triageService,
                       RuleTriageService ruleTriageService,
                       ChatToolCallService toolCallService,
                       @Qualifier("chatStreamExecutor") TaskExecutor chatStreamExecutor,
                       AuditPort auditPort,
                       MetricsPort metrics,
                       ObjectMapper objectMapper,
                       @Value("${CHAT_HISTORY_MAX_MESSAGES:20}") int historyLimit,
                       @Value("${RAG_TOP_K:5}") int contextTopK,
                       @Value("${RAG_SCORE_THRESHOLD:0.35}") double contextScoreThreshold,
                       @Value("${RAG_RELATIVE_SCORE_RATIO:0.8}") double contextRelativeScoreRatio,
                       @Value("${RAG_MAX_CONTEXT_CHARS:6000}") int contextMaxChars,
                       @Value("${CHAT_STREAM_TIMEOUT_SECONDS:120}") long streamTimeoutSeconds,
                       @Value("${LLM_MODEL:deepseek-chat}") String defaultModel) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.citationMapper = citationMapper;
        this.chatEventMapper = chatEventMapper;
        this.kbDocMapper = kbDocMapper;
        this.sequenceMapper = sequenceMapper;
        this.knowledgeService = knowledgeService;
        this.chatStreamPort = chatStreamPort;
        this.triagePort = triagePort;
        this.triageService = triageService;
        this.ruleTriageService = ruleTriageService;
        this.toolCallService = toolCallService;
        this.chatStreamExecutor = chatStreamExecutor;
        this.auditPort = auditPort;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.historyLimit = historyLimit;
        this.contextTopK = contextTopK;
        this.contextScoreThreshold = contextScoreThreshold;
        this.contextRelativeScoreRatio = contextRelativeScoreRatio;
        this.contextMaxChars = contextMaxChars;
        this.streamTimeoutSeconds = streamTimeoutSeconds;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public ChatSessionView createSession(CreateChatSessionRequest request, long userId) {
        long id = IdWorker.getId();
        String sessionNo = nextSessionNo();
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(id);
        session.setTenantId(TENANT);
        session.setSessionNo(sessionNo);
        session.setUserId(userId);
        session.setTitle(request == null ? null : trimToNull(request.title()));
        session.setStatus("ACTIVE");
        session.setMessageCount(0);
        session.setVersion(0);
        session.setCreatedBy(userId);
        session.setUpdatedBy(userId);
        sessionMapper.insert(session);
        writeEvent(id, "CREATED", userId, Map.of("sessionNo", sessionNo));
        auditPort.record(new AuditEvent(userId, null, "CHAT_SESSION_CREATE", "CHAT_SESSION",
                String.valueOf(id), null, Map.of("sessionNo", sessionNo), "SUCCESS", null));
        return toSessionView(sessionMapper.selectById(id));
    }

    public PageResult<ChatSessionView> listSessions(long userId, long pageNo, long pageSize) {
        Page<ChatSessionEntity> page = new Page<>(Math.max(pageNo, 1), Math.min(Math.max(pageSize, 1), 100));
        Page<ChatSessionEntity> result = sessionMapper.selectPage(page,
                Wrappers.<ChatSessionEntity>lambdaQuery()
                        .eq(ChatSessionEntity::getTenantId, TENANT)
                        .eq(ChatSessionEntity::getUserId, userId)
                        .isNull(ChatSessionEntity::getDeletedAt)
                        .orderByDesc(ChatSessionEntity::getUpdatedAt));
        return new PageResult<>(result.getRecords().stream().map(this::toSessionView).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    public ChatSessionView getSession(long sessionId, long userId) {
        return toSessionView(requireOwnedSession(sessionId, userId));
    }

    @Transactional
    public ChatSessionView closeSession(long sessionId, long userId) {
        ChatSessionEntity session = requireOwnedSession(sessionId, userId);
        if ("CLOSED".equals(session.getStatus()) || "ARCHIVED".equals(session.getStatus())) {
            return toSessionView(session);
        }
        sessionMapper.update(null, Wrappers.<ChatSessionEntity>lambdaUpdate()
                .eq(ChatSessionEntity::getId, sessionId)
                .set(ChatSessionEntity::getStatus, "CLOSED")
                .set(ChatSessionEntity::getUpdatedBy, userId)
                .set(ChatSessionEntity::getStreamExpiresAt, null)
                .setSql("version = version + 1"));
        writeEvent(sessionId, "CLOSED", userId, Map.of("from", session.getStatus()));
        auditPort.record(new AuditEvent(userId, null, "CHAT_SESSION_CLOSE", "CHAT_SESSION",
                String.valueOf(sessionId), Map.of("status", session.getStatus()), Map.of("status", "CLOSED"),
                "SUCCESS", null));
        return toSessionView(sessionMapper.selectById(sessionId));
    }

    @Transactional
    public void archiveSession(long sessionId, long userId) {
        ChatSessionEntity session = requireOwnedSession(sessionId, userId);
        sessionMapper.update(null, Wrappers.<ChatSessionEntity>lambdaUpdate()
                .eq(ChatSessionEntity::getId, sessionId)
                .set(ChatSessionEntity::getStatus, "ARCHIVED")
                .set(ChatSessionEntity::getDeletedAt, now())
                .set(ChatSessionEntity::getDeletedBy, userId)
                .set(ChatSessionEntity::getStreamExpiresAt, null)
                .setSql("version = version + 1"));
        writeEvent(sessionId, "ARCHIVED", userId, Map.of("from", session.getStatus()));
    }

    public List<ChatMessageView> listMessages(long sessionId, long userId, Long cursor, int limit) {
        requireOwnedSession(sessionId, userId);
        int size = Math.min(Math.max(limit, 1), 100);
        var query = Wrappers.<ChatMessageEntity>lambdaQuery()
                .eq(ChatMessageEntity::getTenantId, TENANT)
                .eq(ChatMessageEntity::getSessionId, sessionId)
                .in(ChatMessageEntity::getStatus, List.of("COMPLETED", "FAILED"))
                .isNull(ChatMessageEntity::getDeletedAt)
                .orderByDesc(ChatMessageEntity::getId)
                .last("LIMIT " + size);
        if (cursor != null) {
            query.lt(ChatMessageEntity::getId, cursor);
        }
        List<ChatMessageEntity> records = new ArrayList<>(messageMapper.selectList(query));
        Collections.reverse(records);
        return toMessageViews(records);
    }

    /**
     * 发送消息并返回 SSE。会话校验、幂等判定与用户消息落库在返回流之前完成。
     */
    public SseEmitter sendMessage(long sessionId, SendChatMessageRequest request, long userId,
                                  List<String> roles, String traceId) {
        ChatSessionEntity session = requireOwnedSession(sessionId, userId);
        if (!"ACTIVE".equals(session.getStatus()) && !"DEGRADED".equals(session.getStatus())) {
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "会话已结束，请新建会话");
        }
        String content = request.content().trim();
        if (!StringUtils.hasText(content)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "消息内容不能为空");
        }
        String clientMessageId = trimToNull(request.clientMessageId());

        ChatMessageEntity replayAssistant = null;
        if (clientMessageId != null) {
            ChatMessageEntity existingUser = findUserMessageByClientId(sessionId, clientMessageId);
            if (existingUser != null) {
                ChatMessageEntity existingAssistant = findAssistantAfter(sessionId, existingUser.getId());
                if (existingAssistant != null && "COMPLETED".equals(existingAssistant.getStatus())) {
                    replayAssistant = existingAssistant;
                } else if (existingAssistant != null
                        && ("PENDING".equals(existingAssistant.getStatus())
                        || "STREAMING".equals(existingAssistant.getStatus()))) {
                    throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "该消息正在生成中，请勿重复提交");
                }
            }
        }

        if (replayAssistant != null) {
            return buildReplayEmitter(session, replayAssistant, traceId);
        }

        ChatMessageEntity userMessage = findOrInsertUserMessage(session, clientMessageId, content, request.contentType(), userId);
        List<ChatContextChunk> chunks = retrieveContext(content);
        boolean degraded = chunks.isEmpty();
        List<ChatTurn> history = loadHistory(sessionId, userMessage);
        int contextChars = chunks.stream()
                .mapToInt(chunk -> chunk.content() == null ? 0 : chunk.content().length())
                .sum();
        log.info("chat.context session={} trace={} history={} chunks={} contextChars={} degraded={}",
                sessionId, traceId, history.size(), chunks.size(), contextChars, degraded);

        long assistantId = IdWorker.getId();
        ChatMessageEntity assistant = new ChatMessageEntity();
        assistant.setId(assistantId);
        assistant.setTenantId(TENANT);
        assistant.setSessionId(sessionId);
        assistant.setRole("ASSISTANT");
        assistant.setContent("");
        assistant.setContentType("MARKDOWN");
        assistant.setStatus("PENDING");
        assistant.setTraceId(traceId);
        assistant.setTokenIn(0);
        assistant.setTokenOut(0);
        assistant.setStreamExpiresAt(now().plusSeconds(streamTimeoutSeconds));
        assistant.setCreatedBy(userId);
        assistant.setUpdatedBy(userId);
        messageMapper.insert(assistant);

        touchSession(sessionId, 0);
        if (!StringUtils.hasText(session.getTitle())) {
            updateSessionTitle(sessionId, content.length() > 50 ? content.substring(0, 50) : content);
        }

        SseEmitter emitter = new SseEmitter(streamTimeoutSeconds * 1000);
        ChatStreamCommand command = new ChatStreamCommand(traceId, sessionId, assistantId, defaultModel,
                history, chunks, userId, roles == null ? List.of() : roles);
        chatStreamExecutor.execute(() -> runStream(emitter, command, assistantId, sessionId, userId,
                degraded, traceId));
        return emitter;
    }

    private void runStream(SseEmitter emitter, ChatStreamCommand command, long assistantId, long sessionId,
                           long userId, boolean degraded, String traceId) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicLong frameId = new AtomicLong(0);
        AtomicBoolean finalized = new AtomicBoolean(false);
        long startedAt = System.currentTimeMillis();
        StringBuilder answer = new StringBuilder();
        List<ChatCitationView> citations = Collections.synchronizedList(new ArrayList<>());

        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> {
            cancelled.set(true);
            emitter.complete();
        });
        emitter.onError(error -> cancelled.set(true));

        send(emitter, frameId, "meta", Map.of(
                "traceId", traceId == null ? "" : traceId,
                "sessionId", String.valueOf(sessionId),
                "assistantMessageId", String.valueOf(assistantId),
                "model", command.model(),
                "degraded", degraded
        ), cancelled);

        runTriage(emitter, frameId, cancelled, command, assistantId, sessionId, userId, traceId);

        if (cancelled.get()) {
            if (finalized.compareAndSet(false, true)) {
                failAssistant(assistantId, sessionId, userId, "", "CLIENT_DISCONNECT",
                        System.currentTimeMillis() - startedAt, traceId);
            }
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 客户端已断开。
            }
            return;
        }

        ChatStreamHandler handler = new ChatStreamHandler() {
            @Override
            public void onFrame(String event, Map<String, Object> data) {
                if (finalized.get()) {
                    return;
                }
                switch (event) {
                    case "delta" -> {
                        String delta = asString(data.get("content"));
                        if (delta != null && !delta.isEmpty()) {
                            answer.append(delta);
                            send(emitter, frameId, "delta", Map.of("content", delta), cancelled);
                        }
                    }
                    case "citation" -> {
                        ChatCitationView citation = new ChatCitationView(
                                asString(data.get("doc_id")),
                                asString(data.get("chunk_id")),
                                asString(data.get("title")),
                                asDouble(data.get("score")),
                                asInt(data.get("rank")));
                        citations.add(citation);
                        send(emitter, frameId, "citation", citation, cancelled);
                    }
                    case "ping" -> send(emitter, frameId, "ping",
                            Map.of("ts", asLong(data.get("ts"))), cancelled);
                    // 有界任务拆解事件：计划与步骤；前端可忽略，不影响旧协议。
                    case "plan" -> send(emitter, frameId, "plan", data, cancelled);
                    case "step" -> send(emitter, frameId, "step", data, cancelled);
                    case "tool_call" -> handleToolCall(emitter, frameId, cancelled, data,
                            assistantId, sessionId, userId, traceId);
                    case "export_card" -> {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("toolCallId", asString(data.get("tool_call_id")));
                        out.put("summary", asString(data.get("summary")));
                        out.put("filters", asMap(data.get("filters")));
                        out.put("requiresConfirm", true);
                        send(emitter, frameId, "export_card", out, cancelled);
                    }
                    case "done" -> {
                        if (finalized.compareAndSet(false, true)) {
                            int tokenIn = nestedInt(data, "usage", "input_tokens");
                            int tokenOut = nestedInt(data, "usage", "output_tokens");
                            long latencyMs = asLong(data.get("latency_ms"));
                            if (latencyMs <= 0) {
                                latencyMs = System.currentTimeMillis() - startedAt;
                            }
                            completeAssistant(assistantId, sessionId, userId, answer.toString(),
                                    tokenIn, tokenOut, latencyMs, citations, traceId);
                            log.info("chat.done session={} trace={} inputTokens={} outputTokens={} latencyMs={} degraded={}",
                                    sessionId, traceId, tokenIn, tokenOut, latencyMs, degraded);
                            metrics.chatCompleted(tokenIn, tokenOut, latencyMs, degraded);
                            Map<String, Object> usage = new LinkedHashMap<>();
                            usage.put("inputTokens", tokenIn);
                            usage.put("outputTokens", tokenOut);
                            Map<String, Object> done = new LinkedHashMap<>();
                            done.put("finishReason", asString(data.getOrDefault("finish_reason", "stop")));
                            done.put("usage", usage);
                            done.put("messageId", String.valueOf(assistantId));
                            done.put("latencyMs", latencyMs);
                            send(emitter, frameId, "done", done, cancelled);
                        }
                    }
                    case "error" -> {
                        if (finalized.compareAndSet(false, true)) {
                            String internalCode = asString(data.getOrDefault("code", "ai_unavailable"));
                            ErrorCode publicCode = mapAiError(internalCode);
                            long latencyMs = System.currentTimeMillis() - startedAt;
                            failAssistant(assistantId, sessionId, userId, answer.toString(),
                                    internalCode, latencyMs, traceId);
                            log.warn("chat.error session={} trace={} code={} latencyMs={} degraded={}",
                                    sessionId, traceId, internalCode, latencyMs, degraded);
                            metrics.chatFailed(internalCode);
                            send(emitter, frameId, "error", Map.of(
                                    "code", publicCode.getCode(),
                                    "message", publicCode.getMessage(),
                                    "retryable", true,
                                    "degraded", true
                            ), cancelled);
                        }
                    }
                    default -> {
                        // meta 由 Java 下发；未知事件忽略，保持向后兼容。
                    }
                }
            }

            @Override
            public void onError(String internalCode, String message) {
                if (finalized.compareAndSet(false, true)) {
                    ErrorCode publicCode = mapAiError(internalCode);
                    long latencyMs = System.currentTimeMillis() - startedAt;
                    failAssistant(assistantId, sessionId, userId, answer.toString(),
                            internalCode, latencyMs, traceId);
                    log.warn("chat.stream_error session={} trace={} code={} latencyMs={} degraded={}",
                            sessionId, traceId, internalCode, latencyMs, degraded);
                    metrics.chatFailed(internalCode);
                    send(emitter, frameId, "error", Map.of(
                            "code", publicCode.getCode(),
                            "message", publicCode.getMessage(),
                            "retryable", true,
                            "degraded", true
                    ), cancelled);
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };

        try {
            chatStreamPort.stream(command, handler);
        } catch (Exception e) {
            log.warn("chat stream failed, session={}, trace={}", sessionId, traceId, e);
            handler.onError("ai_unavailable", "AI 服务不可用");
        } finally {
            if (finalized.compareAndSet(false, true)) {
                String errorCode = cancelled.get() ? "CLIENT_DISCONNECT" : "STREAM_TIMEOUT";
                long latencyMs = System.currentTimeMillis() - startedAt;
                failAssistant(assistantId, sessionId, userId, answer.toString(), errorCode, latencyMs, traceId);
                send(emitter, frameId, "error", Map.of(
                        "code", mapAiError(errorCode).getCode(),
                        "message", cancelled.get() ? "连接已断开" : "生成超时，请重试",
                        "retryable", true,
                        "degraded", false
                ), cancelled);
            }
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 客户端可能已断开。
            }
        }
    }

    private void handleToolCall(SseEmitter emitter, AtomicLong frameId, AtomicBoolean cancelled,
                                Map<String, Object> data, long assistantId, long sessionId,
                                long userId, String traceId) {
        String toolCallId = asString(data.get("tool_call_id"));
        if (toolCallId == null || toolCallId.isEmpty()) {
            return;
        }
        String name = asString(data.get("name"));
        String status = asString(data.getOrDefault("status", "FAILED"));
        boolean requiresConfirm = Boolean.TRUE.equals(data.get("requires_confirm"));
        Map<String, Object> arguments = asMap(data.get("arguments"));
        String resultSummary = asString(data.get("result_summary"));
        String resultTicketId = asString(data.get("result_ticket_id"));
        String errorCode = asString(data.get("error_code"));
        long resultSizeBytes = asLong(data.get("result_size_bytes"));
        boolean resultTruncated = Boolean.TRUE.equals(data.get("result_truncated"));
        log.info("chat.tool session={} trace={} tool={} status={} resultBytes={} truncated={}",
                sessionId, traceId, name, status, resultSizeBytes, resultTruncated);
        metrics.toolCall(name, status);
        try {
            ChatToolCallEntity entity = toolCallService.recordEvent(
                    sessionId, assistantId, traceId, toolCallId, name, status,
                    arguments, requiresConfirm, resultSummary, resultTicketId, errorCode, userId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", String.valueOf(entity.getId()));
            out.put("toolCallId", toolCallId);
            out.put("name", name);
            out.put("status", entity.getStatus());
            out.put("requiresConfirm", requiresConfirm);
            out.put("arguments", arguments);
            out.put("argumentsSummary", data.get("arguments_summary"));
            out.put("resultSummary", resultSummary);
            out.put("resultTicketId", resultTicketId);
            out.put("errorCode", errorCode);
            send(emitter, frameId, "tool_call", out, cancelled);
        } catch (Exception e) {
            log.warn("tool call persist failed, session={}, tool={}, reason={}",
                    sessionId, toolCallId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private void runTriage(SseEmitter emitter, AtomicLong frameId, AtomicBoolean cancelled,
                           ChatStreamCommand command, long assistantId, long sessionId,
                           long userId, String traceId) {
        TriageResult result;
        try {
            result = triagePort.triage(traceId, command.messages());
        } catch (Exception e) {
            log.warn("triage degraded, using rule fallback, session={}, trace={}, reason={}",
                    sessionId, traceId, e.getMessage());
            result = ruleTriageService.fallback(command.messages());
        }
        if (result == null) {
            return;
        }
        try {
            long triageId = triageService.record(sessionId, assistantId, traceId, result, userId);
            Map<String, Object> triageEvent = new LinkedHashMap<>();
            triageEvent.put("triageId", String.valueOf(triageId));
            triageEvent.put("intent", result.intentCode());
            triageEvent.put("confidence", result.confidence());
            triageEvent.put("action", result.action());
            triageEvent.put("lowConfidence", result.lowConfidence());
            triageEvent.put("degraded", "rule-fallback".equals(result.model()));
            send(emitter, frameId, "triage", triageEvent, cancelled);

            if ("CREATE_DRAFT".equals(result.action()) && result.draft() != null
                    && !result.draft().isEmpty()) {
                Map<String, Object> draft = new LinkedHashMap<>();
                draft.put("title", result.draft().get("title"));
                draft.put("description", result.draft().get("description"));
                draft.put("ticketTypeCode", result.draft().get("ticket_type_code"));
                draft.put("priorityCode", result.draft().get("priority_code"));
                draft.put("locationText", result.draft().get("location_text"));
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("state", "DRAFT");
                card.put("triageId", String.valueOf(triageId));
                card.put("requiresConfirm", true);
                card.put("draft", draft);
                send(emitter, frameId, "ticket_card", card, cancelled);
            }
        } catch (Exception e) {
            log.warn("triage persist failed, session={}, trace={}, reason={}", sessionId, traceId, e.getMessage());
        }
    }

    private SseEmitter buildReplayEmitter(ChatSessionEntity session, ChatMessageEntity assistant, String traceId) {
        SseEmitter emitter = new SseEmitter(30_000L);
        chatStreamExecutor.execute(() -> {
            AtomicBoolean cancelled = new AtomicBoolean(false);
            AtomicLong frameId = new AtomicLong(0);
            emitter.onCompletion(() -> cancelled.set(true));
            send(emitter, frameId, "meta", Map.of(
                    "traceId", traceId == null ? "" : traceId,
                    "sessionId", String.valueOf(session.getId()),
                    "assistantMessageId", String.valueOf(assistant.getId()),
                    "model", defaultModel,
                    "degraded", false
            ), cancelled);
            for (TriageRecordView triage : triageService.listByMessage(assistant.getId())) {
                Map<String, Object> triageEvent = new LinkedHashMap<>();
                triageEvent.put("triageId", triage.id());
                triageEvent.put("intent", triage.intentCode());
                triageEvent.put("confidence", triage.confidence());
                triageEvent.put("action", triage.action());
                triageEvent.put("lowConfidence", triage.lowConfidence());
                send(emitter, frameId, "triage", triageEvent, cancelled);
                if (triage.draft() != null && !triage.draft().isEmpty()) {
                    send(emitter, frameId, "ticket_card", buildTicketCard(triage), cancelled);
                }
            }
            for (ChatToolCallView tool : toolCallService.listByMessage(assistant.getId())) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("id", tool.id());
                event.put("toolCallId", tool.toolCallId());
                event.put("name", tool.name());
                event.put("status", tool.status());
                event.put("requiresConfirm", tool.requiresConfirm());
                event.put("arguments", tool.arguments());
                event.put("argumentsSummary", tool.argumentsSummary());
                event.put("resultSummary", tool.resultSummary());
                event.put("resultTicketId", tool.resultTicketId());
                event.put("errorCode", tool.errorCode());
                send(emitter, frameId, "tool_call", event, cancelled);
            }
            List<ChatCitationView> citations = citationsOf(List.of(assistant.getId()));
            for (ChatCitationView citation : citations) {
                send(emitter, frameId, "citation", citation, cancelled);
            }
            if (StringUtils.hasText(assistant.getContent())) {
                send(emitter, frameId, "delta", Map.of("content", assistant.getContent()), cancelled);
            }
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("inputTokens", assistant.getTokenIn() == null ? 0 : assistant.getTokenIn());
            usage.put("outputTokens", assistant.getTokenOut() == null ? 0 : assistant.getTokenOut());
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("finishReason", "stop");
            done.put("usage", usage);
            done.put("messageId", String.valueOf(assistant.getId()));
            done.put("latencyMs", assistant.getLatencyMs() == null ? 0 : assistant.getLatencyMs());
            send(emitter, frameId, "done", done, cancelled);
            emitter.complete();
        });
        return emitter;
    }

    private Map<String, Object> buildTicketCard(TriageRecordView triage) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("title", triage.draft().get("title"));
        draft.put("description", triage.draft().get("description"));
        draft.put("ticketTypeCode", triage.draft().get("ticket_type_code"));
        draft.put("priorityCode", triage.draft().get("priority_code"));
        draft.put("locationText", triage.draft().get("location_text"));
        Map<String, Object> card = new LinkedHashMap<>();
        boolean confirmed = "CONFIRMED".equals(triage.draftStatus());
        card.put("state", confirmed ? "CREATED" : "DRAFT");
        card.put("triageId", triage.id());
        card.put("requiresConfirm", !confirmed);
        card.put("draft", draft);
        if (confirmed) {
            card.put("ticketId", triage.ticketId());
            card.put("ticketNo", triage.ticketNo());
            card.put("statusCode", triage.ticketStatusCode());
        }
        return card;
    }

    private void completeAssistant(long assistantId, long sessionId, long userId, String content,
                                   int tokenIn, int tokenOut, long latencyMs,
                                   List<ChatCitationView> citations, String traceId) {
        messageMapper.update(null, Wrappers.<ChatMessageEntity>lambdaUpdate()
                .eq(ChatMessageEntity::getId, assistantId)
                .set(ChatMessageEntity::getContent, content)
                .set(ChatMessageEntity::getStatus, "COMPLETED")
                .set(ChatMessageEntity::getTokenIn, tokenIn)
                .set(ChatMessageEntity::getTokenOut, tokenOut)
                .set(ChatMessageEntity::getLatencyMs, (int) Math.min(latencyMs, Integer.MAX_VALUE))
                .set(ChatMessageEntity::getStreamExpiresAt, null)
                .set(ChatMessageEntity::getUpdatedBy, userId));
        persistCitations(assistantId, citations, userId);
        touchSession(sessionId, 1);
        log.info("chat completed, session={}, assistant={}, tokens={}/{}, latency={}ms, trace={}",
                sessionId, assistantId, tokenIn, tokenOut, latencyMs, traceId);
    }

    private void failAssistant(long assistantId, long sessionId, long userId, String content,
                               String errorCode, long latencyMs, String traceId) {
        messageMapper.update(null, Wrappers.<ChatMessageEntity>lambdaUpdate()
                .eq(ChatMessageEntity::getId, assistantId)
                .set(ChatMessageEntity::getContent, content)
                .set(ChatMessageEntity::getStatus, "FAILED")
                .set(ChatMessageEntity::getErrorCode, errorCode)
                .set(ChatMessageEntity::getLatencyMs, (int) Math.min(latencyMs, Integer.MAX_VALUE))
                .set(ChatMessageEntity::getStreamExpiresAt, null)
                .set(ChatMessageEntity::getUpdatedBy, userId));
        touchSession(sessionId, 1);
        log.warn("chat failed, session={}, assistant={}, code={}, trace={}",
                sessionId, assistantId, errorCode, traceId);
    }

    private void persistCitations(long messageId, List<ChatCitationView> citations, long userId) {
        if (citations == null || citations.isEmpty()) {
            return;
        }
        for (ChatCitationView citation : citations) {
            if (citation.chunkId() == null || citation.docId() == null) {
                continue;
            }
            try {
                ChatMessageCitationEntity entity = new ChatMessageCitationEntity();
                entity.setId(IdWorker.getId());
                entity.setTenantId(TENANT);
                entity.setMessageId(messageId);
                entity.setDocId(Long.parseLong(citation.docId()));
                entity.setChunkId(Long.parseLong(citation.chunkId()));
                entity.setScore(citation.score() == null ? null
                        : BigDecimal.valueOf(citation.score()).setScale(4, RoundingMode.HALF_UP));
                entity.setRankNo(citation.rank() == null ? 0 : citation.rank());
                entity.setCreatedBy(userId);
                citationMapper.insert(entity);
            } catch (NumberFormatException ignored) {
                // 非数值 ID 的引用不落库。
            }
        }
    }

    private List<ChatContextChunk> retrieveContext(String query) {
        try {
            List<KnowledgeHitView> hits = knowledgeService.search(
                    new KnowledgeSearchRequest(query, contextTopK, null));
            List<ChatContextChunk> chunks = new ArrayList<>();
            int rank = 1;
            int budget = contextMaxChars;
            // 相对阈值：只保留与最高分接近的片段，避免 0.4~0.5 的无关文档被当作引用展示。
            double topScore = hits.stream()
                    .mapToDouble(hit -> hit.score() == null ? 0.0 : hit.score())
                    .max().orElse(0.0);
            double effectiveThreshold = Math.max(contextScoreThreshold,
                    topScore * contextRelativeScoreRatio);
            for (KnowledgeHitView hit : hits) {
                double score = hit.score() == null ? 0.0 : hit.score();
                if (score < effectiveThreshold) {
                    continue;
                }
                String content = hit.content() == null ? "" : hit.content();
                if (content.length() > budget) {
                    content = content.substring(0, Math.max(budget, 0));
                }
                if (content.isEmpty()) {
                    break;
                }
                chunks.add(new ChatContextChunk(
                        Long.parseLong(hit.docId()),
                        Long.parseLong(hit.chunkId()),
                        hit.title(),
                        content,
                        score,
                        rank++));
                budget -= content.length();
                if (budget <= 0) {
                    break;
                }
            }
            return chunks;
        } catch (Exception e) {
            log.warn("knowledge retrieval degraded: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ChatTurn> loadHistory(long sessionId, ChatMessageEntity currentUser) {
        List<ChatMessageEntity> records = messageMapper.selectList(
                Wrappers.<ChatMessageEntity>lambdaQuery()
                        .eq(ChatMessageEntity::getTenantId, TENANT)
                        .eq(ChatMessageEntity::getSessionId, sessionId)
                        .in(ChatMessageEntity::getRole, List.of("USER", "ASSISTANT"))
                        .eq(ChatMessageEntity::getStatus, "COMPLETED")
                        .isNull(ChatMessageEntity::getDeletedAt)
                        .orderByDesc(ChatMessageEntity::getId)
                        .last("LIMIT " + Math.max(historyLimit, 1)));
        Collections.reverse(records);
        List<ChatTurn> turns = new ArrayList<>();
        boolean currentSeen = false;
        for (ChatMessageEntity record : records) {
            if (record.getId().equals(currentUser.getId())) {
                currentSeen = true;
            }
            if (StringUtils.hasText(record.getContent())) {
                turns.add(new ChatTurn(record.getRole().toLowerCase(), record.getContent()));
            }
        }
        if (!currentSeen) {
            turns.add(new ChatTurn("user", currentUser.getContent()));
        }
        return turns;
    }

    private ChatMessageEntity findOrInsertUserMessage(ChatSessionEntity session, String clientMessageId,
                                                      String content, String contentType, long userId) {
        if (clientMessageId != null) {
            ChatMessageEntity existing = findUserMessageByClientId(session.getId(), clientMessageId);
            if (existing != null) {
                return existing;
            }
        }
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(IdWorker.getId());
        message.setTenantId(TENANT);
        message.setSessionId(session.getId());
        message.setClientMessageId(clientMessageId);
        message.setRole("USER");
        message.setContent(content);
        message.setContentType(StringUtils.hasText(contentType) ? contentType.toUpperCase() : "TEXT");
        message.setStatus("COMPLETED");
        message.setTokenIn(0);
        message.setTokenOut(0);
        message.setCreatedBy(userId);
        message.setUpdatedBy(userId);
        try {
            messageMapper.insert(message);
            bumpSessionMessageCount(session.getId());
        } catch (DuplicateKeyException e) {
            ChatMessageEntity existing = findUserMessageByClientId(session.getId(), clientMessageId);
            if (existing != null) {
                return existing;
            }
            throw e;
        }
        return message;
    }

    private ChatMessageEntity findUserMessageByClientId(long sessionId, String clientMessageId) {
        if (!StringUtils.hasText(clientMessageId)) {
            return null;
        }
        return messageMapper.selectOne(Wrappers.<ChatMessageEntity>lambdaQuery()
                .eq(ChatMessageEntity::getTenantId, TENANT)
                .eq(ChatMessageEntity::getSessionId, sessionId)
                .eq(ChatMessageEntity::getClientMessageId, clientMessageId)
                .eq(ChatMessageEntity::getRole, "USER")
                .last("LIMIT 1"));
    }

    private ChatMessageEntity findAssistantAfter(long sessionId, long messageId) {
        return messageMapper.selectOne(Wrappers.<ChatMessageEntity>lambdaQuery()
                .eq(ChatMessageEntity::getTenantId, TENANT)
                .eq(ChatMessageEntity::getSessionId, sessionId)
                .eq(ChatMessageEntity::getRole, "ASSISTANT")
                .gt(ChatMessageEntity::getId, messageId)
                .orderByAsc(ChatMessageEntity::getId)
                .last("LIMIT 1"));
    }

    private ChatSessionEntity requireOwnedSession(long sessionId, long userId) {
        ChatSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null || session.getDeletedAt() != null
                || !Objects.equals(session.getUserId(), userId)) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "会话不存在或无权访问");
        }
        return session;
    }

    private List<ChatMessageView> toMessageViews(List<ChatMessageEntity> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        List<Long> messageIds = records.stream().map(ChatMessageEntity::getId).toList();
        List<ChatMessageCitationEntity> allCitations = citationMapper.selectList(
                Wrappers.<ChatMessageCitationEntity>lambdaQuery()
                        .in(ChatMessageCitationEntity::getMessageId, messageIds)
                        .orderByAsc(ChatMessageCitationEntity::getRankNo));
        Map<Long, String> docTitles = new HashMap<>();
        if (!allCitations.isEmpty()) {
            List<Long> docIds = allCitations.stream().map(ChatMessageCitationEntity::getDocId).distinct().toList();
            docTitles = kbDocMapper.selectBatchIds(docIds).stream()
                    .collect(Collectors.toMap(KbDocEntity::getId, KbDocEntity::getTitle, (a, b) -> a));
        }
        Map<Long, List<ChatMessageCitationEntity>> byMessage = allCitations.stream()
                .collect(Collectors.groupingBy(ChatMessageCitationEntity::getMessageId));
        Map<Long, List<ChatToolCallView>> toolCalls = toolCallService.viewsByMessageIds(messageIds);
        Map<Long, String> titles = docTitles;
        return records.stream().map(record -> {
            List<ChatCitationView> citations = byMessage.getOrDefault(record.getId(), List.of()).stream()
                    .map(c -> new ChatCitationView(
                            String.valueOf(c.getDocId()),
                            String.valueOf(c.getChunkId()),
                            titles.getOrDefault(c.getDocId(), ""),
                            c.getScore() == null ? null : c.getScore().doubleValue(),
                            c.getRankNo()))
                    .toList();
            return new ChatMessageView(
                    String.valueOf(record.getId()),
                    record.getRole(),
                    record.getContent(),
                    record.getContentType(),
                    record.getStatus(),
                    record.getTokenIn(),
                    record.getTokenOut(),
                    record.getLatencyMs(),
                    record.getErrorCode(),
                    formatTime(record.getCreatedAt()),
                    citations,
                    toolCalls.getOrDefault(record.getId(), List.of())
            );
        }).toList();
    }

    private List<ChatCitationView> citationsOf(List<Long> messageIds) {
        List<ChatMessageCitationEntity> rows = citationMapper.selectList(
                Wrappers.<ChatMessageCitationEntity>lambdaQuery()
                        .in(ChatMessageCitationEntity::getMessageId, messageIds)
                        .orderByAsc(ChatMessageCitationEntity::getRankNo));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> titles = kbDocMapper.selectBatchIds(
                        rows.stream().map(ChatMessageCitationEntity::getDocId).distinct().toList())
                .stream().collect(Collectors.toMap(KbDocEntity::getId, KbDocEntity::getTitle, (a, b) -> a));
        return rows.stream().map(c -> new ChatCitationView(
                String.valueOf(c.getDocId()),
                String.valueOf(c.getChunkId()),
                titles.getOrDefault(c.getDocId(), ""),
                c.getScore() == null ? null : c.getScore().doubleValue(),
                c.getRankNo())).toList();
    }

    private ChatSessionView toSessionView(ChatSessionEntity session) {
        return new ChatSessionView(
                String.valueOf(session.getId()),
                session.getSessionNo(),
                session.getTitle(),
                session.getStatus(),
                session.getMessageCount(),
                formatTime(session.getLastMessageAt()),
                formatTime(session.getCreatedAt())
        );
    }

    private void writeEvent(long sessionId, String eventType, long operatorId, Map<String, Object> payload) {
        ChatEventEntity event = new ChatEventEntity();
        event.setId(IdWorker.getId());
        event.setTenantId(TENANT);
        event.setSessionId(sessionId);
        event.setEventType(eventType);
        event.setOperatorId(operatorId);
        event.setPayloadJson(toJson(payload));
        event.setCreatedBy(operatorId);
        chatEventMapper.insert(event);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void bumpSessionMessageCount(long sessionId) {
        sessionMapper.update(null, Wrappers.<ChatSessionEntity>lambdaUpdate()
                .eq(ChatSessionEntity::getId, sessionId)
                .setSql("message_count = message_count + 1"));
    }

    private void touchSession(long sessionId, int delta) {
        var update = Wrappers.<ChatSessionEntity>lambdaUpdate()
                .eq(ChatSessionEntity::getId, sessionId)
                .set(ChatSessionEntity::getLastMessageAt, now())
                .set(ChatSessionEntity::getStreamExpiresAt, null)
                .setSql("version = version + 1");
        if (delta > 0) {
            update.setSql("message_count = message_count + " + delta);
        }
        sessionMapper.update(null, update);
    }

    private void updateSessionTitle(long sessionId, String title) {
        sessionMapper.update(null, Wrappers.<ChatSessionEntity>lambdaUpdate()
                .eq(ChatSessionEntity::getId, sessionId)
                .set(ChatSessionEntity::getTitle, title));
    }

    private String nextSessionNo() {
        sequenceMapper.ensure(IdWorker.getId(), "CHAT_SESSION_NO");
        sequenceMapper.next("CHAT_SESSION_NO");
        long value = sequenceMapper.lastValue();
        String date = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        return "SESS" + date + String.format("%06d", value);
    }

    private boolean send(SseEmitter emitter, AtomicLong frameId, String event, Object data,
                         AtomicBoolean cancelled) {
        if (cancelled.get()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(frameId.incrementAndGet()))
                    .name(event)
                    .data(data, MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            cancelled.set(true);
            return false;
        }
    }

    private ErrorCode mapAiError(String internalCode) {
        if (internalCode == null) {
            return ErrorCode.AI_UNAVAILABLE;
        }
        return switch (internalCode) {
            case "ai_llm_timeout" -> ErrorCode.AI_LLM_TIMEOUT;
            case "ai_stream_timeout", "STREAM_TIMEOUT", "CLIENT_DISCONNECT" -> ErrorCode.AI_STREAM_TIMEOUT;
            case "kb_vector_store_error" -> ErrorCode.KB_VECTOR_STORE_ERROR;
            case "ai_agent_step_limit" -> ErrorCode.AI_AGENT_STEP_LIMIT;
            case "ai_tool_failed" -> ErrorCode.AI_TOOL_FAILED;
            default -> ErrorCode.AI_UNAVAILABLE;
        };
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? null : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @SuppressWarnings("unchecked")
    private int nestedInt(Map<String, Object> data, String parent, String key) {
        Object nested = data.get(parent);
        if (nested instanceof Map<?, ?> map) {
            Object value = ((Map<String, Object>) map).get(key);
            return asInt(value) == null ? 0 : asInt(value);
        }
        return 0;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private String formatTime(LocalDateTime value) {
        return value == null ? null
                : value.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
