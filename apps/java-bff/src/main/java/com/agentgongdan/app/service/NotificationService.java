package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.SysNotificationEntity;
import com.agentgongdan.adapters.persistence.mapper.SysNotificationMapper;
import com.agentgongdan.adapters.web.dto.NotificationView;
import com.agentgongdan.adapters.web.dto.PageResult;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 站内通知。MVP 采用列表轮询；SSE 后续替换，不影响调用方。
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final long TENANT = 0L;

    private final SysNotificationMapper notificationMapper;

    public NotificationService(SysNotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    /**
     * 通知失败不影响主业务事务；仅记录日志。
     */
    public void notify(long recipientId, String typeCode, String title, String content,
                       String bizType, Long bizId, long actorId) {
        try {
            SysNotificationEntity entity = new SysNotificationEntity();
            entity.setId(IdWorker.getId());
            entity.setTenantId(TENANT);
            entity.setRecipientId(recipientId);
            entity.setTypeCode(typeCode);
            entity.setTitle(title);
            entity.setContent(content);
            entity.setBizType(bizType);
            entity.setBizId(bizId);
            entity.setChannel("IN_APP");
            entity.setCreatedBy(actorId);
            entity.setUpdatedBy(actorId);
            notificationMapper.insert(entity);
        } catch (Exception e) {
            log.warn("notification failed, recipient={}, type={}, reason={}",
                    recipientId, typeCode, e.getMessage());
        }
    }

    public PageResult<NotificationView> list(long userId, boolean unreadOnly, long pageNo, long pageSize) {
        Page<SysNotificationEntity> page = new Page<>(Math.max(pageNo, 1),
                Math.min(Math.max(pageSize, 1), 100));
        var query = Wrappers.<SysNotificationEntity>lambdaQuery()
                .eq(SysNotificationEntity::getTenantId, TENANT)
                .eq(SysNotificationEntity::getRecipientId, userId)
                .isNull(SysNotificationEntity::getDeletedAt)
                .orderByDesc(SysNotificationEntity::getCreatedAt);
        if (unreadOnly) {
            query.isNull(SysNotificationEntity::getReadAt);
        }
        Page<SysNotificationEntity> result = notificationMapper.selectPage(page, query);
        List<NotificationView> items = result.getRecords().stream().map(this::toView).toList();
        return new PageResult<>(items, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public long unreadCount(long userId) {
        return notificationMapper.selectCount(Wrappers.<SysNotificationEntity>lambdaQuery()
                .eq(SysNotificationEntity::getTenantId, TENANT)
                .eq(SysNotificationEntity::getRecipientId, userId)
                .isNull(SysNotificationEntity::getReadAt)
                .isNull(SysNotificationEntity::getDeletedAt));
    }

    public void markRead(long id, long userId) {
        int rows = notificationMapper.update(null, Wrappers.<SysNotificationEntity>lambdaUpdate()
                .eq(SysNotificationEntity::getId, id)
                .eq(SysNotificationEntity::getTenantId, TENANT)
                .eq(SysNotificationEntity::getRecipientId, userId)
                .isNull(SysNotificationEntity::getReadAt)
                .set(SysNotificationEntity::getReadAt, LocalDateTime.now(ZoneOffset.UTC))
                .set(SysNotificationEntity::getUpdatedBy, userId));
        if (rows == 0) {
            boolean exists = notificationMapper.exists(Wrappers.<SysNotificationEntity>lambdaQuery()
                    .eq(SysNotificationEntity::getId, id)
                    .eq(SysNotificationEntity::getRecipientId, userId));
            if (!exists) {
                throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "通知不存在或无权访问");
            }
        }
    }

    public int markAllRead(long userId) {
        return notificationMapper.update(null, Wrappers.<SysNotificationEntity>lambdaUpdate()
                .eq(SysNotificationEntity::getTenantId, TENANT)
                .eq(SysNotificationEntity::getRecipientId, userId)
                .isNull(SysNotificationEntity::getReadAt)
                .set(SysNotificationEntity::getReadAt, LocalDateTime.now(ZoneOffset.UTC))
                .set(SysNotificationEntity::getUpdatedBy, userId));
    }

    private NotificationView toView(SysNotificationEntity entity) {
        return new NotificationView(
                String.valueOf(entity.getId()),
                entity.getTypeCode(),
                entity.getTitle(),
                entity.getContent(),
                entity.getBizType(),
                entity.getBizId() == null ? null : String.valueOf(entity.getBizId()),
                formatTime(entity.getReadAt()),
                formatTime(entity.getCreatedAt())
        );
    }

    private String formatTime(LocalDateTime value) {
        return value == null ? null
                : value.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
