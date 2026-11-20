package com.agentgongdan.adapters.web.controller;

import com.agentgongdan.adapters.persistence.entity.SysUserEntity;
import com.agentgongdan.adapters.persistence.mapper.SysUserMapper;
import com.agentgongdan.adapters.web.dto.UserOptionView;
import com.agentgongdan.common.api.ApiResponse;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内部人员查询：供 Agent 工具把“张三/engineer1”解析为用户 ID。
 * 只返回 ID、账号、显示名，不返回手机号、密码等敏感字段。
 */
@RestController
@RequestMapping("/internal/v1/users")
public class InternalUserController {

    private final SysUserMapper userMapper;

    public InternalUserController(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @GetMapping
    public ApiResponse<List<UserOptionView>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "10") int limit) {
        var query = Wrappers.<SysUserEntity>lambdaQuery()
                .eq(SysUserEntity::getStatus, "ACTIVE")
                .isNull(SysUserEntity::getDeletedAt);
        if (StringUtils.hasText(keyword)) {
            String like = keyword.trim();
            query.and(wrapper -> wrapper.like(SysUserEntity::getUsername, like)
                    .or().like(SysUserEntity::getDisplayName, like));
        }
        int size = Math.min(Math.max(limit, 1), 20);
        query.orderByAsc(SysUserEntity::getDisplayName).last("LIMIT " + size);
        List<UserOptionView> users = userMapper.selectList(query).stream()
                .map(user -> new UserOptionView(
                        String.valueOf(user.getId()),
                        user.getUsername(),
                        user.getDisplayName()))
                .toList();
        return ApiResponse.ok(users);
    }
}
