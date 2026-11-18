package com.agentgongdan.app.service;

import com.agentgongdan.adapters.web.dto.TicketExportDownloadView;
import com.agentgongdan.app.ports.storage.ObjectStoragePort;
import com.alibaba.excel.EasyExcel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 工单导出任务：生成 Excel、写入项目 MinIO bucket 的 exports/ 前缀并返回短时效 URL。
 */
@Service
public class TicketExportJobService {

    private static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final TicketExportService ticketExportService;
    private final ObjectStoragePort storage;
    private final int presignExpireSeconds;

    public TicketExportJobService(TicketExportService ticketExportService,
                                  ObjectStoragePort storage,
                                  @Value("${EXPORT_PRESIGN_EXPIRE_SECONDS:600}") int presignExpireSeconds) {
        this.ticketExportService = ticketExportService;
        this.storage = storage;
        this.presignExpireSeconds = Math.max(60, presignExpireSeconds);
    }

    public TicketExportDownloadView export(String status, String keyword,
                                           String priorityCode, String ticketTypeCode,
                                           String location, Long assigneeId, Long scopeUserId,
                                           LocalDateTime startTime, LocalDateTime endTime) {
        List<List<String>> rows = ticketExportService.exportRows(status, keyword, priorityCode,
                ticketTypeCode, location, assigneeId, scopeUserId, startTime, endTime);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        EasyExcel.write(buffer)
                .head(TicketExportService.HEADERS.stream().map(List::of).toList())
                .sheet("工单")
                .doWrite(rows);
        byte[] bytes = buffer.toByteArray();
        String filename = "tickets-" + LocalDateTime.now().format(FILE_TIME) + ".xlsx";
        String objectKey = "exports/" + UUID.randomUUID().toString().replace("-", "")
                + "-" + filename;
        storage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, CONTENT_TYPE);
        String url = storage.presignedGetUrl(objectKey, presignExpireSeconds);
        return new TicketExportDownloadView(url, filename, rows.size(), presignExpireSeconds);
    }
}
