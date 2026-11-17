package com.agentgongdan.domain.user;

/**
 * 认证所需的用户领域快照（不含手机号等敏感信息）。
 */
public record UserAccount(
        long id,
        String username,
        String passwordHash,
        String displayName,
        String status
) {
}
