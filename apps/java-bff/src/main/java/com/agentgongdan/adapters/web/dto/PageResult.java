package com.agentgongdan.adapters.web.dto;

import java.util.List;

public record PageResult<T>(
        List<T> items,
        long total,
        long page,
        long pageSize
) {
}
