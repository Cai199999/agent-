package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.IdempotencyRecordEntity;
import com.agentgongdan.adapters.persistence.entity.TicketCommentEntity;
import com.agentgongdan.adapters.persistence.entity.SysUserEntity;
import com.agentgongdan.adapters.persistence.entity.TicketEntity;
import com.agentgongdan.adapters.persistence.entity.TicketEventEntity;
import com.agentgongdan.adapters.persistence.mapper.BizSequenceMapper;
import com.agentgongdan.adapters.persistence.mapper.IdempotencyRecordMapper;
import com.agentgongdan.adapters.persistence.mapper.TicketCommentMapper;
import com.agentgongdan.adapters.persistence.mapper.TicketEventMapper;
import com.agentgongdan.adapters.persistence.mapper.SysUserMapper;
import com.agentgongdan.adapters.persistence.mapper.TicketMapper;
import com.agentgongdan.adapters.web.dto.CreateTicketRequest;
import com.agentgongdan.adapters.web.dto.PageResult;
import com.agentgongdan.adapters.web.dto.TicketCommentView;
import com.agentgongdan.adapters.web.dto.TicketDetail;
import com.agentgongdan.adapters.web.dto.TicketEventView;
import com.agentgongdan.adapters.web.dto.TicketSlaView;
import com.agentgongdan.adapters.web.dto.TicketSummary;
import com.agentgongdan.adapters.web.dto.TransitionRequest;
import com.agentgongdan.app.ports.audit.AuditPort;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.agentgongdan.domain.audit.AuditEvent;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TicketService {

    private static final String TENANT = "0";
    private static final Set<String> TERMINAL = Set.of("CLOSED", "CANCELLED");

    private final TicketMapper ticketMapper;
    private final TicketEventMapper eventMapper;
    private final TicketCommentMapper commentMapper;
    private final BizSequenceMapper sequenceMapper;
    private final IdempotencyRecordMapper idempotencyMapper;
    private final SysUserMapper userMapper;
    private final NotificationService notificationService;
    private final TicketVectorService ticketVectorService;
    private final UserProfileService userProfileService;
    private final ObjectMapper objectMapper;
    private final AuditPort auditPort;

    public TicketService(TicketMapper ticketMapper,
                         TicketEventMapper eventMapper,
                         TicketCommentMapper commentMapper,
                         BizSequenceMapper sequenceMapper,
                         IdempotencyRecordMapper idempotencyMapper,
                         SysUserMapper userMapper,
                         NotificationService notificationService,
                         TicketVectorService ticketVectorService,
                         UserProfileService userProfileService,
                         ObjectMapper objectMapper,
                         AuditPort auditPort) {
        this.ticketMapper = ticketMapper;
        this.eventMapper = eventMapper;
        this.commentMapper = commentMapper;
        this.sequenceMapper = sequenceMapper;
        this.idempotencyMapper = idempotencyMapper;
        this.userMapper = userMapper;
        this.notificationService = notificationService;
        this.ticketVectorService = ticketVectorService;
        this.userProfileService = userProfileService;
        this.objectMapper = objectMapper;
        this.auditPort = auditPort;
    }

    @Transactional
    public TicketDetail create(CreateTicketRequest request, long userId, String idempotencyKey) {
        return createInternal(request, userId, idempotencyKey, "FORM", null, null, null);
    }

    @Transactional
    public TicketDetail createFromChat(CreateTicketRequest request, long userId, String idempotencyKey,
                                       long sessionId, long triageId) {
        return createInternal(request, userId, idempotencyKey, "CHAT", "CHAT_SESSION", sessionId, triageId);
    }

    @Transactional
    public TicketDetail createFromTool(CreateTicketRequest request, long userId, String idempotencyKey,
                                       long sessionId) {
        return createInternal(request, userId, idempotencyKey, "AGENT", "CHAT_SESSION", sessionId, null);
    }

    @Transactional
    public TicketDetail createFromAgent(CreateTicketRequest request, long userId, String idempotencyKey) {
        return createInternal(request, userId, idempotencyKey, "AGENT", null, null, null);
    }

    private TicketDetail createInternal(CreateTicketRequest request, long userId, String idempotencyKey,
                                        String sourceCode, String sourceRefType, Long sourceRefId,
                                        Long triageId) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "缺少 Idempotency-Key");
        }
        String scope = "TICKET_CREATE";
        String requestHash = hashRequest(request, userId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long recordId = IdWorker.getId();
        int inserted = idempotencyMapper.tryInsert(
                recordId, idempotencyKey, scope, requestHash, now.plusHours(24));
        if (inserted == 0) {
            IdempotencyRecordEntity existing = idempotencyMapper.find(scope, idempotencyKey);
            if (existing == null) {
                throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "幂等记录状态异常，请稍后重试");
            }
            if (!requestHash.equals(existing.getRequestHash())) {
                throw new ApiException(ErrorCode.TICKET_IDEMPOTENCY_CONFLICT);
            }
            if (!"SUCCESS".equals(existing.getStatus()) || !StringUtils.hasText(existing.getResponseJson())) {
                throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "相同请求正在处理，请稍后重试");
            }
            return detail(parseTicketId(existing.getResponseJson()));
        }

        long ticketId = IdWorker.getId();
        String ticketNo = nextTicketNo();
        String priority = StringUtils.hasText(request.priorityCode()) ? request.priorityCode() : "NORMAL";
        UserProfileService.ProfileSnapshot profile = userProfileService.snapshot(userId);
        String title = StringUtils.hasText(request.title())
                ? request.title().trim() : defaultTitle(request.description());
        Long projectId = firstNonNull(request.projectId(), profile.projectId());
        Long buildingId = firstNonNull(request.buildingId(), profile.buildingId());
        Long customerId = firstNonNull(request.customerId(), profile.customerId());
        Long roomId = firstNonNull(request.roomId(), profile.roomId());
        String contactName = firstText(request.contactName(), profile.contactName());

        TicketEntity ticket = new TicketEntity();
        ticket.setId(ticketId);
        ticket.setTenantId(0L);
        ticket.setTicketNo(ticketNo);
        ticket.setTitle(title);
        ticket.setDescription(request.description());
        ticket.setTicketTypeCode(request.ticketTypeCode());
        ticket.setPriorityCode(priority);
        ticket.setStatusCode("PENDING");
        ticket.setSourceCode(sourceCode);
        ticket.setSourceRefType(sourceRefType);
        ticket.setSourceRefId(sourceRefId);
        ticket.setTriageId(triageId);
        ticket.setReporterId(userId);
        ticket.setProjectId(projectId);
        ticket.setBuildingId(buildingId);
        ticket.setCustomerId(customerId);
        ticket.setRoomId(roomId);
        ticket.setEquipmentId(request.equipmentId());
        ticket.setServiceItemId(request.serviceItemId());
        ticket.setServiceItemText(StringUtils.hasText(request.serviceItemText())
                ? request.serviceItemText().trim() : null);
        ticket.setWorkflowId(request.workflowId());
        ticket.setCurrentNodeCode("ACCEPT");
        ticket.setAppointmentAt(parseOptionalUtc(request.appointmentAt()));
        ticket.setContactName(contactName);
        ticket.setContactPhoneEnc(profile.phoneEnc());
        ticket.setContactPhoneMask(profile.phoneMask());
        ticket.setAddressText(StringUtils.hasText(request.addressText()) ? request.addressText().trim() : null);
        ticket.setIndoorPublicArea(StringUtils.hasText(request.indoorPublicArea())
                ? request.indoorPublicArea().trim().toUpperCase() : null);
        ticket.setLocationType(StringUtils.hasText(request.locationType())
                ? request.locationType().trim().toUpperCase() : null);
        ticket.setLocationText(request.locationText());
        ticket.setSlaResponseBreached(0);
        ticket.setSlaResolveBreached(0);
        ticket.setHoldMinutes(0);
        ticket.setSlaResponseDueAt(now.plusHours("URGENT".equals(priority) ? 1 : 4));
        ticket.setSlaResolveDueAt(now.plusHours("URGENT".equals(priority) ? 4 : 24));
        ticket.setTaskDueAt(ticket.getSlaResolveDueAt());
        ticket.setVersion(0);
        ticketMapper.insert(ticket);

        insertEvent(ticketId, "CREATED", null, "PENDING", userId, "创建工单", now);
        idempotencyMapper.markSuccess(recordId, toResponseJson(ticketId, ticketNo));
        auditPort.record(new AuditEvent(
                userId, null, "TICKET_CREATE", "TICKET", String.valueOf(ticketId),
                null, Map.of("ticketNo", ticketNo, "status", "PENDING"), "SUCCESS", null));
        ticketVectorService.indexAsync(ticket);
        return detail(ticketId);
    }

    /**
     * 内部 API：按 actor 的 dataScope 过滤；非特权角色仅能看到本人报障的工单。
     */
    public List<TicketSummary> listInternal(long actorId, List<String> roles, String status,
                                            String keyword, int limit) {
        int size = Math.min(Math.max(limit, 1), 20);
        var query = Wrappers.<TicketEntity>lambdaQuery()
                .eq(TicketEntity::getTenantId, 0L)
                .isNull(TicketEntity::getDeletedAt);
        if (!isPrivileged(roles)) {
            query.eq(TicketEntity::getReporterId, actorId);
        }
        if (StringUtils.hasText(status)) {
            query.eq(TicketEntity::getStatusCode, status);
        }
        if (StringUtils.hasText(keyword)) {
            query.like(TicketEntity::getTitle, keyword);
        }
        query.orderByDesc(TicketEntity::getCreatedAt).last("LIMIT " + size);
        return ticketMapper.selectList(query).stream().map(this::toSummary).toList();
    }

    public TicketDetail detailInternal(long ticketId, long actorId, List<String> roles) {
        TicketEntity ticket = requireTicket(ticketId);
        if (!isPrivileged(roles) && !Objects.equals(ticket.getReporterId(), actorId)) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "无权查看该工单");
        }
        return detail(ticketId);
    }

    private boolean isPrivileged(List<String> roles) {
        if (roles == null) {
            return false;
        }
        return roles.contains("ADMIN") || roles.contains("DISPATCHER")
                || roles.contains("ENGINEER") || roles.contains("INSPECTOR");
    }

    public PageResult<TicketSummary> list(long pageNo, long pageSize, String status, String keyword,
                                          String priorityCode, String ticketTypeCode,
                                          Long assigneeId, Long mineUserId,
                                          LocalDateTime startTime, LocalDateTime endTime) {
        Page<TicketEntity> page = new Page<>(pageNo, pageSize);
        var query = Wrappers.<TicketEntity>lambdaQuery()
                .eq(TicketEntity::getTenantId, 0L)
                .isNull(TicketEntity::getDeletedAt)
                .orderByDesc(TicketEntity::getCreatedAt);
        if (StringUtils.hasText(status)) {
            query.eq(TicketEntity::getStatusCode, status);
        }
        if (StringUtils.hasText(keyword)) {
            String like = keyword.trim();
            query.and(wrapper -> wrapper.like(TicketEntity::getTitle, like)
                    .or().like(TicketEntity::getTicketNo, like));
        }
        if (StringUtils.hasText(priorityCode)) {
            query.eq(TicketEntity::getPriorityCode, priorityCode);
        }
        if (StringUtils.hasText(ticketTypeCode)) {
            query.eq(TicketEntity::getTicketTypeCode, ticketTypeCode);
        }
        if (assigneeId != null) {
            query.eq(TicketEntity::getAssigneeId, assigneeId);
        }
        if (mineUserId != null) {
            query.and(wrapper -> wrapper.eq(TicketEntity::getReporterId, mineUserId)
                    .or().eq(TicketEntity::getAssigneeId, mineUserId));
        }
        if (startTime != null) {
            query.ge(TicketEntity::getCreatedAt, startTime);
        }
        if (endTime != null) {
            query.le(TicketEntity::getCreatedAt, endTime);
        }
        Page<TicketEntity> result = ticketMapper.selectPage(page, query);
        List<TicketSummary> items = result.getRecords().stream().map(this::toSummary).toList();
        return new PageResult<>(items, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public TicketDetail detail(long ticketId) {
        TicketEntity ticket = requireTicket(ticketId);
        List<TicketEventView> events = eventMapper.selectList(Wrappers.<TicketEventEntity>lambdaQuery()
                        .eq(TicketEventEntity::getTicketId, ticketId)
                        .orderByAsc(TicketEventEntity::getId))
                .stream()
                .map(this::toEventView)
                .toList();
        Map<Long, String> names = userNames(ticket.getReporterId(), ticket.getAssigneeId());
        TicketSlaView sla = new TicketSlaView(
                iso(ticket.getSlaResponseDueAt()),
                iso(ticket.getSlaResolveDueAt()),
                flag(ticket.getSlaResponseBreached()),
                flag(ticket.getSlaResolveBreached()),
                iso(ticket.getFirstResponseAt()),
                iso(ticket.getAcceptedAt()),
                iso(ticket.getResolvedAt()),
                iso(ticket.getClosedAt()));
        return new TicketDetail(toSummary(ticket), ticket.getDescription(), events,
                names.get(ticket.getReporterId()), names.get(ticket.getAssigneeId()),
                ticket.getHoldMinutes(), ticket.getHoldReason(), sla);
    }

    public List<TicketCommentView> listComments(long ticketId) {
        requireTicket(ticketId);
        return commentMapper.selectList(Wrappers.<TicketCommentEntity>lambdaQuery()
                        .eq(TicketCommentEntity::getTicketId, ticketId)
                        .isNull(TicketCommentEntity::getDeletedAt)
                        .orderByAsc(TicketCommentEntity::getId))
                .stream()
                .map(this::toCommentView)
                .toList();
    }

    @Transactional
    public TicketCommentView addComment(long ticketId, String content, String visibility, long authorId) {
        requireTicket(ticketId);
        if (!StringUtils.hasText(content)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "评论内容不能为空");
        }
        String actualVisibility = StringUtils.hasText(visibility) ? visibility : "PUBLIC";
        if (!Set.of("PUBLIC", "INTERNAL").contains(actualVisibility)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "visibility 仅支持 PUBLIC/INTERNAL");
        }
        TicketCommentEntity comment = new TicketCommentEntity();
        comment.setId(IdWorker.getId());
        comment.setTenantId(0L);
        comment.setTicketId(ticketId);
        comment.setAuthorId(authorId);
        comment.setVisibility(actualVisibility);
        comment.setContent(content);
        commentMapper.insert(comment);

        auditPort.record(new AuditEvent(
                authorId, null, "TICKET_COMMENT", "TICKET", String.valueOf(ticketId),
                null, Map.of("commentId", String.valueOf(comment.getId()), "visibility", actualVisibility),
                "SUCCESS", null));
        return toCommentView(commentMapper.selectById(comment.getId()));
    }

    @Transactional
    public TicketDetail transition(long ticketId, String action, TransitionRequest request, long userId) {
        TicketEntity ticket = requireTicket(ticketId);
        if (!request.expectedVersion().equals(ticket.getVersion())) {
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "工单版本已变化，请刷新后重试");
        }

        String from = ticket.getStatusCode();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String to = from;
        Long assignee = ticket.getAssigneeId();
        LocalDateTime acceptedAt = ticket.getAcceptedAt();
        LocalDateTime resolvedAt = ticket.getResolvedAt();
        LocalDateTime closedAt = ticket.getClosedAt();
        String closeReason = ticket.getCloseReasonCode();
        String cancelReason = ticket.getCancelReason();
        LocalDateTime holdStartedAt = ticket.getHoldStartedAt();
        String holdReason = ticket.getHoldReason();
        Integer holdMinutes = ticket.getHoldMinutes();
        String onsiteSituation = ticket.getOnsiteSituation();
        String serviceItemText = ticket.getServiceItemText();
        LocalDateTime slaResponseDueAt = ticket.getSlaResponseDueAt();
        LocalDateTime slaResolveDueAt = ticket.getSlaResolveDueAt();
        String reason = request.reason();

        switch (action) {
            case "assign" -> {
                requireStatus(from, Set.of("PENDING", "PENDING_ACCEPT"), "当前状态不可派单");
                to = "PENDING_ACCEPT";
                assignee = request.assigneeId() != null ? request.assigneeId() : userId;
            }
            case "accept" -> {
                requireStatus(from, Set.of("PENDING_ACCEPT"), "当前状态不可接单");
                to = "PROCESSING";
                assignee = assignee != null ? assignee : userId;
                acceptedAt = now;
            }
            case "resolve" -> {
                requireStatus(from, Set.of("PROCESSING"), "当前状态不可提交解决");
                if (!StringUtils.hasText(request.serviceItemText())) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "服务项目不能为空");
                }
                to = "PENDING_VERIFY";
                resolvedAt = now;
                serviceItemText = request.serviceItemText().trim();
                if (StringUtils.hasText(request.onsiteSituation())) {
                    onsiteSituation = request.onsiteSituation().trim();
                }
                reason = reason == null ? "提交解决" : reason;
            }
            case "verify" -> {
                requireStatus(from, Set.of("PENDING_VERIFY"), "当前状态不可验证");
                boolean approved = Boolean.TRUE.equals(request.approved());
                to = approved ? "RESOLVED" : "PROCESSING";
                reason = reason == null ? (approved ? "验证通过" : "验证不通过") : reason;
            }
            case "close" -> {
                requireStatus(from, Set.of("RESOLVED", "PENDING_VERIFY"), "当前状态不可关闭");
                to = "CLOSED";
                closedAt = now;
                closeReason = StringUtils.hasText(request.closeReasonCode())
                        ? request.closeReasonCode() : "SOLVED";
                reason = reason == null ? "关闭工单" : reason;
            }
            case "cancel" -> {
                if (TERMINAL.contains(from)) {
                    throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "终态工单不可取消");
                }
                to = "CANCELLED";
                cancelReason = StringUtils.hasText(request.cancelReason())
                        ? request.cancelReason() : reason;
                reason = reason == null ? "取消工单" : reason;
            }
            case "hold" -> {
                requireStatus(from, Set.of("PENDING_ACCEPT", "PROCESSING"), "当前状态不可挂起");
                to = "SUSPENDED";
                holdStartedAt = now;
                holdReason = StringUtils.hasText(request.reason()) ? request.reason() : "挂起";
                reason = holdReason;
            }
            case "finish" -> {
                if (TERMINAL.contains(from)) {
                    throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "终态工单不可结束");
                }
                to = "CLOSED";
                closedAt = now;
                closeReason = "MANUAL";
                reason = StringUtils.hasText(request.reason()) ? request.reason() : "管理员结束工单";
            }
            case "resume" -> {
                requireStatus(from, Set.of("SUSPENDED"), "当前状态不可恢复");
                to = "PROCESSING";
                if (holdStartedAt != null) {
                    long minutes = Math.max(Duration.between(holdStartedAt, now).toMinutes(), 0);
                    holdMinutes = (holdMinutes == null ? 0 : holdMinutes) + (int) minutes;
                    if (slaResponseDueAt != null) {
                        slaResponseDueAt = slaResponseDueAt.plusMinutes(minutes);
                    }
                    if (slaResolveDueAt != null) {
                        slaResolveDueAt = slaResolveDueAt.plusMinutes(minutes);
                    }
                }
                holdStartedAt = null;
                reason = StringUtils.hasText(request.reason()) ? request.reason() : "恢复处理";
            }
            default -> throw new ApiException(ErrorCode.VALIDATION_ERROR, "不支持的操作");
        }

        LambdaUpdateWrapper<TicketEntity> update = Wrappers.<TicketEntity>lambdaUpdate()
                .eq(TicketEntity::getId, ticketId)
                .eq(TicketEntity::getVersion, ticket.getVersion())
                .eq(TicketEntity::getStatusCode, from)
                .set(TicketEntity::getStatusCode, to)
                .set(TicketEntity::getVersion, ticket.getVersion() + 1)
                .set(TicketEntity::getUpdatedAt, now)
                .set(TicketEntity::getAssigneeId, assignee)
                .set(TicketEntity::getAcceptedAt, acceptedAt)
                .set(TicketEntity::getResolvedAt, resolvedAt)
                .set(TicketEntity::getClosedAt, closedAt)
                .set(TicketEntity::getCloseReasonCode, closeReason)
                .set(TicketEntity::getCancelReason, cancelReason)
                .set(TicketEntity::getHoldStartedAt, holdStartedAt)
                .set(TicketEntity::getHoldReason, holdReason)
                .set(TicketEntity::getHoldMinutes, holdMinutes)
                .set(TicketEntity::getOnsiteSituation, onsiteSituation)
                .set(TicketEntity::getServiceItemText, serviceItemText)
                .set(TicketEntity::getSlaResponseDueAt, slaResponseDueAt)
                .set(TicketEntity::getSlaResolveDueAt, slaResolveDueAt)
                .set(TicketEntity::getUpdatedBy, userId);
        int rows = ticketMapper.update(null, update);
        if (rows == 0) {
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "工单状态已变化，请刷新后重试");
        }
        insertEvent(ticketId, action.toUpperCase(), from, to, userId, reason, now);
        auditPort.record(new AuditEvent(
                userId, null, "TICKET_" + action.toUpperCase(), "TICKET", String.valueOf(ticketId),
                Map.of("status", from), Map.of("status", to), "SUCCESS", null));
        notifyTransition(ticket, action, from, to, assignee, userId);
        ticketVectorService.indexAsync(ticketMapper.selectById(ticketId));
        return detail(ticketId);
    }

    private void notifyTransition(TicketEntity ticket, String action, String from, String to,
                                  Long assignee, long actorId) {
        LinkedHashSet<Long> recipients = new LinkedHashSet<>();
        switch (action) {
            case "assign" -> addIfPresent(recipients, assignee);
            case "accept", "resolve", "hold", "resume" ->
                    addIfPresent(recipients, ticket.getReporterId());
            case "verify" -> addIfPresent(recipients, assignee);
            case "close", "cancel" -> {
                addIfPresent(recipients, ticket.getReporterId());
                addIfPresent(recipients, assignee);
            }
            default -> {
                // 其他动作暂不通知
            }
        }
        recipients.remove(actorId);
        if (recipients.isEmpty()) {
            return;
        }
        String title = switch (action) {
            case "assign" -> "工单已派给你";
            case "accept" -> "工单已被接单";
            case "resolve" -> "工单已提交解决";
            case "verify" -> "工单验证结果已更新";
            case "close" -> "工单已关闭";
            case "cancel" -> "工单已取消";
            case "hold" -> "工单已挂起";
            case "resume" -> "工单已恢复处理";
            default -> "工单状态已更新";
        };
        String content = "工单 " + ticket.getTicketNo() + "《" + ticket.getTitle() + "》"
                + from + " → " + to;
        for (Long recipient : recipients) {
            notificationService.notify(recipient, "TICKET_" + action.toUpperCase(),
                    title, content, "TICKET", ticket.getId(), actorId);
        }
    }

    private void addIfPresent(LinkedHashSet<Long> recipients, Long userId) {
        if (userId != null) {
            recipients.add(userId);
        }
    }

    private Map<Long, String> userNames(Long... ids) {
        List<Long> distinct = Arrays.stream(ids).filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(SysUserEntity::getId, SysUserEntity::getDisplayName));
    }

    private Boolean flag(Integer value) {
        return value != null && value == 1;
    }

    private TicketEntity requireTicket(long ticketId) {
        TicketEntity ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || ticket.getDeletedAt() != null) {
            throw new ApiException(ErrorCode.TICKET_NOT_FOUND);
        }
        return ticket;
    }

    private void requireStatus(String current, Set<String> allowed, String message) {
        if (!allowed.contains(current)) {
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, message);
        }
    }

    private String nextTicketNo() {
        sequenceMapper.ensure(IdWorker.getId(), "TICKET_NO");
        sequenceMapper.next("TICKET_NO");
        long value = sequenceMapper.lastValue();
        String date = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        return "GD" + date + String.format("%06d", value);
    }

    private void insertEvent(long ticketId, String eventType, String from, String to,
                             long operatorId, String reason, LocalDateTime now) {
        TicketEventEntity event = new TicketEventEntity();
        event.setId(IdWorker.getId());
        event.setTenantId(0L);
        event.setTicketId(ticketId);
        event.setEventType(eventType);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setOperatorId(operatorId);
        event.setReason(reason);
        event.setTraceId(MDC.get("traceId"));
        event.setCreatedAt(now);
        event.setCreatedBy(operatorId);
        eventMapper.insert(event);
    }

    private TicketSummary toSummary(TicketEntity ticket) {
        return new TicketSummary(
                String.valueOf(ticket.getId()),
                ticket.getTicketNo(),
                ticket.getTitle(),
                ticket.getStatusCode(),
                ticket.getPriorityCode(),
                ticket.getTicketTypeCode(),
                str(ticket.getReporterId()),
                str(ticket.getAssigneeId()),
                str(ticket.getCustomerId()),
                str(ticket.getRoomId()),
                str(ticket.getEquipmentId()),
                ticket.getLocationText(),
                iso(ticket.getCreatedAt()),
                iso(ticket.getUpdatedAt()),
                iso(ticket.getSlaResolveDueAt()),
                ticket.getVersion(),
                str(ticket.getProjectId()),
                str(ticket.getBuildingId()),
                str(ticket.getServiceItemId()),
                ticket.getCurrentNodeCode(),
                iso(ticket.getAppointmentAt()),
                iso(ticket.getReceivedAt()),
                iso(ticket.getArrivedAt()),
                iso(ticket.getTaskDueAt()),
                ticket.getContactName(),
                ticket.getContactPhoneMask(),
                ticket.getAddressText(),
                ticket.getIndoorPublicArea(),
                ticket.getLocationType(),
                ticket.getServiceItemText(),
                ticket.getOnsiteSituation()
        );
    }

    private TicketEventView toEventView(TicketEventEntity event) {
        return new TicketEventView(
                String.valueOf(event.getId()),
                event.getEventType(),
                event.getFromStatus(),
                event.getToStatus(),
                str(event.getOperatorId()),
                event.getReason(),
                iso(event.getCreatedAt())
        );
    }

    private TicketCommentView toCommentView(TicketCommentEntity comment) {
        return new TicketCommentView(
                String.valueOf(comment.getId()),
                String.valueOf(comment.getTicketId()),
                str(comment.getParentId()),
                str(comment.getAuthorId()),
                comment.getVisibility(),
                comment.getContent(),
                iso(comment.getCreatedAt())
        );
    }

    private String str(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private LocalDateTime parseOptionalUtc(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private String iso(LocalDateTime value) {
        return value == null ? null
                : value.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private String hashRequest(CreateTicketRequest request, long userId) {
        String raw = String.join("|",
                String.valueOf(userId),
                safe(request.title()),
                safe(request.description()),
                safe(request.ticketTypeCode()),
                safe(request.priorityCode()),
                safe(request.locationText()),
                safe(request.customerId()),
                safe(request.roomId()),
                safe(request.equipmentId()),
                safe(request.projectId()),
                safe(request.buildingId()),
                safe(request.serviceItemId()),
                safe(request.workflowId()),
                safe(request.appointmentAt()),
                safe(request.contactName()),
                safe(request.addressText()),
                safe(request.indoorPublicArea()),
                safe(request.locationType()),
                safe(request.serviceItemText()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.SYS_INTERNAL_ERROR);
        }
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultTitle(String description) {
        if (!StringUtils.hasText(description)) {
            return "报修工单";
        }
        String text = description.replaceAll("\\s+", " ").trim();
        return text.length() > 40 ? text.substring(0, 40) : text;
    }

    private String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred.trim() : fallback;
    }

    private <T> T firstNonNull(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }

    private String toResponseJson(long ticketId, String ticketNo) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "ticketId", String.valueOf(ticketId),
                    "ticketNo", ticketNo
            ));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.SYS_INTERNAL_ERROR);
        }
    }

    private long parseTicketId(String responseJson) {
        try {
            var node = objectMapper.readTree(responseJson).get("ticketId");
            if (node == null) {
                throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "幂等记录缺少工单 ID");
            }
            return Long.parseLong(node.asText());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.SYS_INTERNAL_ERROR);
        }
    }
}
