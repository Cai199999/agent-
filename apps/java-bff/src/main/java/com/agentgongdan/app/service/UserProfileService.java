package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.SysUserEntity;
import com.agentgongdan.adapters.persistence.entity.SysUserProfileEntity;
import com.agentgongdan.adapters.persistence.mapper.SysUserMapper;
import com.agentgongdan.adapters.persistence.mapper.SysUserProfileMapper;
import com.agentgongdan.adapters.security.PiiCipher;
import com.agentgongdan.adapters.web.dto.UpdateUserProfileRequest;
import com.agentgongdan.adapters.web.dto.UserProfileView;
import com.agentgongdan.app.ports.audit.AuditPort;
import com.agentgongdan.domain.audit.AuditEvent;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 报修人个人资料：姓名、电话、项目/楼宇/房间/客户绑定。
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
            return new UserProfileView(fallbackName, null, null, null, null, null);
        }
        return new UserProfileView(
                StringUtils.hasText(entity.getContactName()) ? entity.getContactName() : fallbackName,
                entity.getPhoneMask(),
                str(entity.getProjectId()),
                str(entity.getBuildingId()),
                str(entity.getRoomId()),
                str(entity.getCustomerId())
        );
    }

    @Transactional
    public UserProfileView update(long userId, UpdateUserProfileRequest request) {
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
        entity.setUpdatedBy(userId);
        if (insert) {
            profileMapper.insert(entity);
        } else {
            profileMapper.updateById(entity);
        }
        auditPort.record(new AuditEvent(
                userId, null, "PROFILE_UPDATE", "SYS_USER", String.valueOf(userId),
                null, Map.of("hasPhone", StringUtils.hasText(request.phone())),
                "SUCCESS", null));
        return get(userId);
    }

    /**
     * 建单时自动带出；无资料时返回空视图（姓名回退显示名）。
     */
    public UserProfileView profileForTicket(long userId) {
        return get(userId);
    }

    public ProfileSnapshot snapshot(long userId) {
        SysUserProfileEntity entity = find(userId);
        String displayName = displayName(userId);
        if (entity == null) {
            return new ProfileSnapshot(displayName, null, null, null, null, null, null);
        }
        return new ProfileSnapshot(
                StringUtils.hasText(entity.getContactName()) ? entity.getContactName() : displayName,
                entity.getPhoneEnc(),
                entity.getPhoneMask(),
                entity.getProjectId(),
                entity.getBuildingId(),
                entity.getRoomId(),
                entity.getCustomerId()
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
}
