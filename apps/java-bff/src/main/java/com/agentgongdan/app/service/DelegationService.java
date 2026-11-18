package com.agentgongdan.app.service;

import com.agentgongdan.adapters.web.dto.DelegationTokenView;
import com.agentgongdan.app.ports.security.DelegationTokenPort;
import com.agentgongdan.app.ports.security.IssuedDelegation;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 委托令牌应用服务：签发短时 Token，Actor/角色/scope 由令牌声明，请求头不能覆盖。
 */
@Service
public class DelegationService {

    private static final long DEFAULT_TTL_SECONDS = 600;

    private final DelegationTokenPort tokenPort;

    public DelegationService(DelegationTokenPort tokenPort) {
        this.tokenPort = tokenPort;
    }

    public boolean enabled() {
        return tokenPort.enabled();
    }

    public DelegationTokenView issue(long actorId, List<String> roles,
                                     List<String> scopes, Long ttlSeconds) {
        if (!tokenPort.enabled()) {
            throw new ApiException(ErrorCode.SYS_DEPENDENCY_UNAVAILABLE, "委托令牌未启用");
        }
        long ttl = ttlSeconds == null ? DEFAULT_TTL_SECONDS : ttlSeconds;
        IssuedDelegation issued = tokenPort.issue(actorId,
                roles == null ? List.of() : roles,
                scopes == null || scopes.isEmpty() ? List.of("*") : scopes,
                ttl);
        return new DelegationTokenView(
                issued.token(),
                issued.claims().delegationId(),
                Instant.ofEpochSecond(issued.claims().expiresAt()).toString());
    }
}
