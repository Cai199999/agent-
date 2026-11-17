package com.agentgongdan.adapters.persistence.repository;

import com.agentgongdan.adapters.persistence.entity.SysPermissionEntity;
import com.agentgongdan.adapters.persistence.entity.SysRoleEntity;
import com.agentgongdan.adapters.persistence.entity.SysRolePermissionEntity;
import com.agentgongdan.adapters.persistence.entity.SysUserEntity;
import com.agentgongdan.adapters.persistence.entity.SysUserRoleEntity;
import com.agentgongdan.adapters.persistence.mapper.SysPermissionMapper;
import com.agentgongdan.adapters.persistence.mapper.SysRoleMapper;
import com.agentgongdan.adapters.persistence.mapper.SysRolePermissionMapper;
import com.agentgongdan.adapters.persistence.mapper.SysUserMapper;
import com.agentgongdan.adapters.persistence.mapper.SysUserRoleMapper;
import com.agentgongdan.app.ports.repository.UserRepositoryPort;
import com.agentgongdan.domain.user.UserAccount;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户/角色/权限仓储适配器。
 * 关联查询全部为“主表 + 批量 IN + 内存组装”，不写多表 JOIN。
 */
@Repository
public class UserRepositoryAdapter implements UserRepositoryPort {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysRolePermissionMapper rolePermissionMapper;

    public UserRepositoryAdapter(SysUserMapper userMapper,
                                 SysRoleMapper roleMapper,
                                 SysUserRoleMapper userRoleMapper,
                                 SysPermissionMapper permissionMapper,
                                 SysRolePermissionMapper rolePermissionMapper) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        SysUserEntity entity = userMapper.selectOne(Wrappers.<SysUserEntity>lambdaQuery()
                .eq(SysUserEntity::getUsername, username)
                .eq(SysUserEntity::getDeletedFlag, 0L)
                .last("LIMIT 1"));
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public Optional<UserAccount> findById(long userId) {
        SysUserEntity entity = userMapper.selectById(userId);
        if (entity == null || entity.getDeletedFlag() == null || entity.getDeletedFlag() != 0L) {
            return Optional.empty();
        }
        return Optional.of(toDomain(entity));
    }

    @Override
    public List<String> findRoleCodes(long userId) {
        List<Long> roleIds = findRoleIds(userId);
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectBatchIds(roleIds).stream()
                .filter(role -> "ACTIVE".equals(role.getStatus()) && role.getDeletedAt() == null)
                .map(SysRoleEntity::getRoleCode)
                .distinct()
                .toList();
    }

    @Override
    public List<String> findPermissionCodes(long userId) {
        List<Long> roleIds = findRoleIds(userId);
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<Long> permissionIds = rolePermissionMapper.selectList(
                        Wrappers.<SysRolePermissionEntity>lambdaQuery()
                                .in(SysRolePermissionEntity::getRoleId, roleIds))
                .stream()
                .map(SysRolePermissionEntity::getPermissionId)
                .distinct()
                .toList();
        if (permissionIds.isEmpty()) {
            return List.of();
        }
        return permissionMapper.selectBatchIds(permissionIds).stream()
                .filter(permission -> "ACTIVE".equals(permission.getStatus())
                        && permission.getDeletedAt() == null)
                .map(SysPermissionEntity::getPermissionCode)
                .distinct()
                .toList();
    }

    private List<Long> findRoleIds(long userId) {
        return userRoleMapper.selectList(Wrappers.<SysUserRoleEntity>lambdaQuery()
                        .eq(SysUserRoleEntity::getUserId, userId))
                .stream()
                .map(SysUserRoleEntity::getRoleId)
                .distinct()
                .toList();
    }

    private UserAccount toDomain(SysUserEntity entity) {
        return new UserAccount(
                entity.getId(),
                entity.getUsername(),
                entity.getPasswordHash(),
                entity.getDisplayName(),
                entity.getStatus()
        );
    }
}
