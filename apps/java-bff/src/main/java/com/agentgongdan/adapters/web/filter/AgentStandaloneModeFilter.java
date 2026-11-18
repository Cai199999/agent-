package com.agentgongdan.adapters.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 独立 Agent 部署模式：只暴露 /agent/** 与 Agent Card/健康检查，隐藏用户端 API。
 * 由 `agent.deployment.standalone=true` 激活，普通 BFF 部署不受影响。
 */
@Component
@ConditionalOnProperty(name = "agent.deployment.standalone", havingValue = "true")
public class AgentStandaloneModeFilter extends OncePerRequestFilter {

    private static final List<String> ALLOWED_PREFIXES = List.of(
            "/agent/",
            "/actuator/health",
            "/actuator/info"
    );
    private static final List<String> ALLOWED_EXACT = List.of(
            "/.well-known/agent-card.json",
            "/agent-card.json",
            "/error"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean allowed = ALLOWED_EXACT.contains(path)
                || ALLOWED_PREFIXES.stream().anyMatch(path::startsWith);
        if (allowed) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":404,\"message\":\"standalone agent service only exposes /agent/**\",\"data\":null}");
    }
}
