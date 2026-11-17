package com.agentgongdan.adapters.security;

import cn.dev33.satoken.stp.StpInterface;
import com.agentgongdan.app.ports.repository.UserRepositoryPort;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 权限/角色来源：按用户 ID 查询数据库，无 JOIN。
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    private final UserRepositoryPort userRepository;

    public StpInterfaceImpl(UserRepositoryPort userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return userRepository.findPermissionCodes(toUserId(loginId));
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return userRepository.findRoleCodes(toUserId(loginId));
    }

    private long toUserId(Object loginId) {
        return Long.parseLong(String.valueOf(loginId));
    }
}
