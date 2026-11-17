package com.agentgongdan.app.service;

import cn.dev33.satoken.stp.StpUtil;
import com.agentgongdan.app.dto.AuthResult;
import com.agentgongdan.app.ports.audit.AuditPort;
import com.agentgongdan.app.ports.repository.UserRepositoryPort;
import com.agentgongdan.app.ports.security.PasswordHasherPort;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.agentgongdan.domain.audit.AuditEvent;
import com.agentgongdan.domain.user.UserAccount;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthService {

    private static final long ACCESS_TOKEN_EXPIRE_SECONDS = 7200L;

    private final UserRepositoryPort userRepository;
    private final PasswordHasherPort passwordHasher;
    private final AuditPort auditPort;

    public AuthService(UserRepositoryPort userRepository,
                       PasswordHasherPort passwordHasher,
                       AuditPort auditPort) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.auditPort = auditPort;
    }

    public AuthResult login(String username, String password) {
        UserAccount user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            auditLoginFailure(username, ErrorCode.AUTH_LOGIN_FAILED);
            throw new ApiException(ErrorCode.AUTH_LOGIN_FAILED);
        }
        if (!"ACTIVE".equals(user.status())) {
            auditLoginFailure(username, ErrorCode.AUTH_ACCOUNT_LOCKED);
            throw new ApiException(ErrorCode.AUTH_ACCOUNT_LOCKED);
        }
        if (!passwordHasher.matches(password, user.passwordHash())) {
            auditLoginFailure(username, ErrorCode.AUTH_LOGIN_FAILED);
            throw new ApiException(ErrorCode.AUTH_LOGIN_FAILED);
        }

        auditPort.record(new AuditEvent(
                user.id(), user.username(), "LOGIN", "USER",
                String.valueOf(user.id()), null, null, "SUCCESS", null));
        StpUtil.login(user.id());
        String accessToken = StpUtil.getTokenValue();
        List<String> roles = userRepository.findRoleCodes(user.id());
        List<String> permissions = userRepository.findPermissionCodes(user.id());
        return new AuthResult(
                accessToken,
                ACCESS_TOKEN_EXPIRE_SECONDS,
                String.valueOf(user.id()),
                user.username(),
                user.displayName(),
                roles,
                permissions
        );
    }

    private void auditLoginFailure(String username, ErrorCode errorCode) {
        auditPort.record(new AuditEvent(
                null, username, "LOGIN", "USER", username, null, null,
                "FAILED", String.valueOf(errorCode.getCode())));
    }

    public AuthResult me() {
        StpUtil.checkLogin();
        long userId = StpUtil.getLoginIdAsLong();
        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_UNAUTHORIZED));
        List<String> roles = userRepository.findRoleCodes(userId);
        List<String> permissions = userRepository.findPermissionCodes(userId);
        return new AuthResult(
                null,
                0L,
                String.valueOf(user.id()),
                user.username(),
                user.displayName(),
                roles,
                permissions
        );
    }
}
