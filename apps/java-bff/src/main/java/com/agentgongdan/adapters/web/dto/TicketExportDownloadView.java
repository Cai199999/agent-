package com.agentgongdan.adapters.web.dto;

/**
 * 内部/AI 导出结果：短时效预签名下载地址。
 */
public record TicketExportDownloadView(
        String url,
        String filename,
        int rowCount,
        int expiresInSeconds
) {
}
