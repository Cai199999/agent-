package com.agentgongdan.app.ports.repository;

import com.agentgongdan.domain.user.UserAccount;

import java.util.List;
import java.util.Optional;

public interface UserRepositoryPort {

    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findById(long userId);

    List<String> findRoleCodes(long userId);

    List<String> findPermissionCodes(long userId);
}
