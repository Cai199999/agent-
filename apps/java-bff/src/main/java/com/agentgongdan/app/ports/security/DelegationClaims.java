package com.agentgongdan.app.ports.security;

import java.util.List;

/**
 * 委托身份声明：调用方代表某个真实 Actor，只允许在 scope 内调用。
 */
public record DelegationClaims(
        String delegationId,
        long actorId,
        List<String> roles,
        List<String> scopes,
        long issuedAt,
        long expiresAt
) {
    public boolean allows(String capability) {
        return scopes != null && (scopes.contains("*") || scopes.contains(capability));
    }
}
