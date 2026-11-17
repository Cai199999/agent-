package com.agentgongdan.adapters.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agentgongdan.adapters.persistence.entity.SysUserEntity;
import com.agentgongdan.adapters.persistence.mapper.SysUserMapper;
import com.agentgongdan.adapters.web.dto.UserOptionView;
import com.agentgongdan.common.api.ApiResponse;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final SysUserMapper userMapper;

    public UserController(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @GetMapping("/options")
    public ApiResponse<List<UserOptionView>> options() {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:read");
        List<UserOptionView> options = userMapper.selectList(Wrappers.<SysUserEntity>lambdaQuery()
                        .eq(SysUserEntity::getStatus, "ACTIVE")
                        .isNull(SysUserEntity::getDeletedAt)
                        .orderByAsc(SysUserEntity::getDisplayName)
                        .last("LIMIT 200"))
                .stream()
                .map(user -> new UserOptionView(
                        String.valueOf(user.getId()),
                        user.getUsername(),
                        user.getDisplayName()))
                .toList();
        return ApiResponse.ok(options);
    }
}
