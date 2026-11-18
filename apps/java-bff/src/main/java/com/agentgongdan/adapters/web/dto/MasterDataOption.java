package com.agentgongdan.adapters.web.dto;

/**
 * 主数据下拉选项；parentId 用于前端级联过滤。
 */
public record MasterDataOption(
        String id,
        String code,
        String name,
        String parentId,
        String extra
) {
}
