package com.agentgongdan.adapters.security;

import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * `/internal/**` 鉴权：校验 Python→Java 方向内部令牌，并注入 actor 上下文。
 */
@Component
public class InternalTokenInterceptor implements HandlerInterceptor {

    private final Set<String> tokens;

    public InternalTokenInterceptor(
            @Value("${INTERNAL_TOKEN_P2J_CURRENT:}") String current,
            @Value("${INTERNAL_TOKEN_P2J_NEXT:}") String next) {
        this.tokens = java.util.stream.Stream.of(current, next)
                .filter(StringUtils::hasText)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader("X-Internal-Token");
        if (!StringUtils.hasText(token) || !tokens.contains(token)) {
            throw new ApiException(ErrorCode.AUTH_UNAUTHORIZED, "内部令牌无效");
        }
        long actorId = 0L;
        String actorHeader = request.getHeader("X-Actor-Id");
        if (StringUtils.hasText(actorHeader)) {
            try {
                actorId = Long.parseLong(actorHeader.trim());
            } catch (NumberFormatException ignored) {
                actorId = 0L;
            }
        }
        String rolesHeader = request.getHeader("X-Actor-Roles");
        List<String> roles = StringUtils.hasText(rolesHeader)
                ? Arrays.stream(rolesHeader.split(",")).map(String::trim)
                .filter(StringUtils::hasText).toList()
                : List.of();
        request.setAttribute("internalActorId", actorId);
        request.setAttribute("internalActorRoles", roles);
        return true;
    }
}
