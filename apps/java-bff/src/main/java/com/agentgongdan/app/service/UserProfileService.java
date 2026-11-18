package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.SysUserEntity;
import com.agentgongdan.adapters.persistence.entity.SysUserProfileEntity;
import com.agentgongdan.adapters.persistence.mapper.SysUserMapper;
import com.agentgongdan.adapters.persistence.mapper.SysUserProfileMapper;
import com.agentgongdan.adapters.security.PiiCipher;
import com.agentgongdan.adapters.web.dto.ProfileReviewView;
import com.agentgongdan.adapters.web.dto.ReviewProfileRequest;
import com.agentgongdan.adapters.web.dto.UpdateUserProfileRequest;
import com.agentgongdan.adapters.web.dto.UserProfileView;
import com.agentgongdan.app.ports.audit.AuditPort;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.agentgongdan.domain.audit.AuditEvent;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 报修人个人资料：姓名、电话、项目/楼宇/房间/客户绑定。
 * 绑定关系变更需管理员审核；审核通过后才用于建单自动带出。
 */
@Service
public class UserProfileService {

    private static final long TENANT = 0L;

    private final SysUserProfileMapper profileMapper;
    private final SysUserMapper userMapper;
    private final PiiCipher piiCipher;
    private final AuditPort auditPort;

    public UserProfileService(SysUserProfileMapper profileMapper,
                              SysUserMapper userMapper,
                              PiiCipher piiCipher,
                              AuditPort auditPort) {
        this.profileMapper = profileMapper;
        this.userMapper = userMapper;
        this.piiCipher = piiCipher;
        this.auditPort = auditPort;
    }

    public UserProfileView get(long userId) {
        SysUserProfileEntity entity = find(userId);
        String fallbackName = displayName(userId);
        if (entity == null) {
            return new UserProfileView(fallbackName, null, null, null, null, null,
                    "UNSET", null);
        }
        return new UserProfileView(
                StringUtils.hasText(entity.getContactName()) ? entity.getContactName() : fallbackName,
                entity.getPhoneMask(),
                str(entity.getProjectId()),
                str(entity.getBuildingId()),
                str(entity.getRoomId()),
                str(entity.getCustomerId()),
                entity.getReviewStatus() == null ? "PENDING" : entity.getReviewStatus(),
                entity.getReviewRemark()
        );
    }

    @Transactional
    public UserProfileView update(long userId, UpdateUserProfileRequest request, boolean admin) {
        if (request == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "请求不能为空");
        }
        SysUserProfileEntity entity = find(userId);
        boolean insert = entity == null;
        if (insert) {
            entity = new SysUserProfileEntity();
            entity.setId(IdWorker.getId());
            entity.setTenantId(TENANT);
            entity.setUserId(userId);
            entity.setStatus("ACTIVE");
            entity.setCreatedBy(userId);
        }

        boolean bindingChanged = insert
                ? hasAnyBinding(request)
                : !Objects.equals(entity.getProjectId(), request.projectId())
                || !Objects.equals(entity.getBuildingId(), request.buildingId())
                || !Objects.equals(entity.getRoomId(), request.roomId())
                || !Objects.equals(entity.getCustomerId(), request.customerId());

        if (StringUtils.hasText(request.contactName())) {
            entity.setContactName(request.contactName().trim());
        }
        if (StringUtils.hasText(request.phone())) {
            String phone = request.phone().trim();
            entity.setPhoneEnc(piiCipher.encrypt(phone));
            entity.setPhoneMask(piiCipher.maskPhone(phone));
        }
        entity.setProjectId(request.projectId());
        entity.setBuildingId(request.buildingId());
        entity.setRoomId(request.roomId());
        entity.setCustomerId(request.customerId());

        if (admin) {
            entity.setReviewStatus("APPROVED");
            entity.setReviewedBy(userId);
            entity.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        } else if (bindingChanged) {
            entity.setReviewStatus("PENDING");
            entity.setReviewRemark(null);
            entity.setReviewedBy(null);
            entity.setReviewedAt(null);
        } else if (insert) {
            entity.setReviewStatus("APPROVED");
        }
        entity.setUpdatedBy(userId);
        if (insert) {
            profileMapper.insert(entity);
        } else {
            profileMapper.updateById(entity);
        }
        auditPort.record(new AuditEvent(
                userId, null, "PROFILE_UPDATE", "SYS_USER", String.valueOf(userId),
                null, Map.of("hasPhone", StringUtils.hasText(request.phone()),
                        "reviewStatus", entity.getReviewStatus()),
                "SUCCESS", null));
        return get(userId);
    }

    @Transactional
    public void review(long userId, ReviewProfileRequest request, long reviewerId) {
        if (request == null || request.approved() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "审核结果不能为空");
        }
        SysUserProfileEntity entity = find(userId);
        if (entity == null) {
            throw new ApiException(ErrorCode.TICKET_NOT_FOUND, "用户资料不存在");
        }
        entity.setReviewStatus(Boolean.TRUE.equals(request.approved()) ? "APPROVED" : "REJECTED");
        entity.setReviewRemark(StringUtils.hasText(request.remark()) ? request.remark().trim() : null);
        entity.setReviewedBy(reviewerId);
        entity.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedBy(reviewerId);
        profileMapper.updateById(entity);
        auditPort.record(new AuditEvent(
                reviewerId, null, "PROFILE_REVIEW", "SYS_USER", String.valueOf(userId),
                null, Map.of("approved", Boolean.TRUE.equals(request.approved()),
                        "remark", entity.getReviewRemark() == null ? "" : entity.getReviewRemark()),
                "SUCCESS", null));
    }

    public List<ProfileReviewView> pendingReviews() {
        List<SysUserProfileEntity> rows = profileMapper.selectList(
                Wrappers.<SysUserProfileEntity>lambdaQuery()
                        .eq(SysUserProfileEntity::getTenantId, TENANT)
                        .eq(SysUserProfileEntity::getReviewStatus, "PENDING")
                        .eq(SysUserProfileEntity::getStatus, "ACTIVE")
                        .isNull(SysUserProfileEntity::getDeletedAt)
                        .orderByAsc(SysUserProfileEntity::getUpdatedAt)
                        .last("LIMIT 100"));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, SysUserEntity> users = userMapper.selectBatchIds(
                        rows.stream().map(SysUserProfileEntity::getUserId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(SysUserEntity::getId, item -> item));
        return rows.stream().map(item -> {
            SysUserEntity user = users.get(item.getUserId());
            return new ProfileReviewView(
                    String.valueOf(item.getUserId()),
                    user == null ? null : user.getUsername(),
                    user == null ? null : user.getDisplayName(),
                    item.getContactName(),
                    item.getPhoneMask(),
                    str(item.getProjectId()),
                    str(item.getBuildingId()),
                    str(item.getRoomId()),
                    str(item.getCustomerId()),
                    item.getReviewStatus(),
                    iso(item.getCreatedAt())
            );
        }).toList();
    }

    /**
     * 建单时自动带出；只有 APPROVED 的绑定关系才参与自动填充。
     */
    public ProfileSnapshot snapshot(long userId) {
        SysUserProfileEntity entity = find(userId);
        String displayName = displayName(userId);
        if (entity == null) {
            return new ProfileSnapshot(displayName, null, null, null, null, null, null);
        }
        boolean approved = "APPROVED".equals(entity.getReviewStatus());
        return new ProfileSnapshot(
                StringUtils.hasText(entity.getContactName()) ? entity.getContactName() : displayName,
                entity.getPhoneEnc(),
                entity.getPhoneMask(),
                approved ? entity.getProjectId() : null,
                approved ? entity.getBuildingId() : null,
                approved ? entity.getRoomId() : null,
                approved ? entity.getCustomerId() : null
        );
    }

    public record ProfileSnapshot(
            String contactName,
            String phoneEnc,
            String phoneMask,
            Long projectId,
            Long buildingId,
            Long roomId,
            Long customerId
    ) {
    }

    private boolean hasAnyBinding(UpdateUserProfileRequest request) {
        return request.projectId() != null || request.buildingId() != null
                || request.roomId() != null || request.customerId() != null;
    }

    private SysUserProfileEntity find(long userId) {
        return profileMapper.selectOne(Wrappers.<SysUserProfileEntity>lambdaQuery()
                .eq(SysUserProfileEntity::getTenantId, TENANT)
                .eq(SysUserProfileEntity::getUserId, userId)
                .isNull(SysUserProfileEntity::getDeletedAt)
                .last("LIMIT 1"));
    }

    private String displayName(long userId) {
        SysUserEntity user = userMapper.selectById(userId);
        return user == null ? null : user.getDisplayName();
    }

    private String str(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private String iso(LocalDateTime value) {
        return value == null ? null
                : value.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
