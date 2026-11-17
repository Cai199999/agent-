package com.agentgongdan.adapters.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agentgongdan.adapters.web.dto.NotificationView;
import com.agentgongdan.adapters.web.dto.PageResult;
import com.agentgongdan.app.service.NotificationService;
import com.agentgongdan.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ApiResponse<PageResult<NotificationView>> list(
            @RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        StpUtil.checkLogin();
        return ApiResponse.ok(notificationService.list(
                StpUtil.getLoginIdAsLong(), unread, page, Math.min(pageSize, 100)));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount() {
        StpUtil.checkLogin();
        return ApiResponse.ok(Map.of("count", notificationService.unreadCount(StpUtil.getLoginIdAsLong())));
    }

    @PutMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable long id) {
        StpUtil.checkLogin();
        notificationService.markRead(id, StpUtil.getLoginIdAsLong());
        return ApiResponse.ok(null);
    }

    @PutMapping("/read-all")
    public ApiResponse<Map<String, Integer>> markAllRead() {
        StpUtil.checkLogin();
        int updated = notificationService.markAllRead(StpUtil.getLoginIdAsLong());
        return ApiResponse.ok(Map.of("updated", updated));
    }
}
