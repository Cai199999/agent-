package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.FileObjectEntity;
import com.agentgongdan.adapters.persistence.entity.TicketAttachmentEntity;
import com.agentgongdan.adapters.persistence.entity.TicketEntity;
import com.agentgongdan.adapters.persistence.mapper.FileObjectMapper;
import com.agentgongdan.adapters.persistence.mapper.TicketAttachmentMapper;
import com.agentgongdan.adapters.persistence.mapper.TicketMapper;
import com.agentgongdan.adapters.web.dto.AttachmentView;
import com.agentgongdan.app.ports.audit.AuditPort;
import com.agentgongdan.app.ports.storage.ObjectStoragePort;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.agentgongdan.domain.audit.AuditEvent;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AttachmentService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "md", "png", "jpg");

    private final TicketMapper ticketMapper;
    private final TicketAttachmentMapper attachmentMapper;
    private final FileObjectMapper fileObjectMapper;
    private final ObjectStoragePort storage;
    private final AuditPort auditPort;
    private final long maxSizeMb;
    private final int presignExpirySeconds;
    private final String appEnv;

    public AttachmentService(TicketMapper ticketMapper,
                             TicketAttachmentMapper attachmentMapper,
                             FileObjectMapper fileObjectMapper,
                             ObjectStoragePort storage,
                             AuditPort auditPort,
                             @Value("${UPLOAD_MAX_SIZE_MB:10}") long maxSizeMb,
                             @Value("${MINIO_PRESIGN_EXPIRE_SECONDS:600}") int presignExpirySeconds,
                             @Value("${APP_ENV:dev}") String appEnv) {
        this.ticketMapper = ticketMapper;
        this.attachmentMapper = attachmentMapper;
        this.fileObjectMapper = fileObjectMapper;
        this.storage = storage;
        this.auditPort = auditPort;
        this.maxSizeMb = maxSizeMb;
        this.presignExpirySeconds = presignExpirySeconds;
        this.appEnv = appEnv;
    }

    @Transactional
    public AttachmentView upload(long ticketId, MultipartFile file, long userId) {
        requireTicket(ticketId);
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "文件不能为空");
        }
        if (file.getSize() > maxSizeMb * 1024 * 1024) {
            throw new ApiException(ErrorCode.VALIDATION_FILE_SIZE);
        }
        String originalName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "file";
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ApiException(ErrorCode.VALIDATION_FILE_TYPE);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "读取文件失败");
        }
        if (!magicMatches(extension, bytes)) {
            throw new ApiException(ErrorCode.VALIDATION_FILE_TYPE, "文件内容与扩展名不匹配");
        }

        String objectKey = "agent-gongdan/" + appEnv + "/TICKET_ATTACHMENT/"
                + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy/MM"))
                + "/" + UUID.randomUUID() + "." + extension;
        String contentType = contentTypeOf(extension, file.getContentType());
        String checksum = sha256(bytes);

        long fileId = IdWorker.getId();
        long attachmentId = IdWorker.getId();
        storage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, contentType);

        try {
            FileObjectEntity fileObject = new FileObjectEntity();
            fileObject.setId(fileId);
            fileObject.setTenantId(0L);
            fileObject.setObjectKey(objectKey);
            fileObject.setBucket("agent-gongdan");
            fileObject.setOriginalName(originalName);
            fileObject.setContentType(contentType);
            fileObject.setSizeBytes((long) bytes.length);
            fileObject.setChecksumSha256(checksum);
            fileObject.setStorageProvider("MINIO");
            fileObject.setBizType("TICKET_ATTACHMENT");
            fileObject.setBizId(ticketId);
            fileObject.setStatus("ACTIVE");
            fileObject.setUploadedBy(userId);
            fileObjectMapper.insert(fileObject);

            TicketAttachmentEntity attachment = new TicketAttachmentEntity();
            attachment.setId(attachmentId);
            attachment.setTenantId(0L);
            attachment.setTicketId(ticketId);
            attachment.setFileId(fileId);
            attachment.setAttachmentType("GENERAL");
            attachment.setCreatedBy(userId);
            attachmentMapper.insert(attachment);

            auditPort.record(new AuditEvent(
                    userId, null, "TICKET_ATTACHMENT_UPLOAD", "TICKET", String.valueOf(ticketId),
                    null, Map.of("attachmentId", String.valueOf(attachmentId),
                            "fileName", originalName, "size", bytes.length),
                    "SUCCESS", null));
        } catch (RuntimeException e) {
            storage.remove(objectKey);
            throw e;
        }

        FileObjectEntity saved = fileObjectMapper.selectById(fileId);
        return toView(attachmentMapper.selectById(attachmentId), saved);
    }

    public List<AttachmentView> list(long ticketId) {
        requireTicket(ticketId);
        List<TicketAttachmentEntity> attachments = attachmentMapper.selectList(
                Wrappers.<TicketAttachmentEntity>lambdaQuery()
                        .eq(TicketAttachmentEntity::getTicketId, ticketId)
                        .isNull(TicketAttachmentEntity::getDeletedAt)
                        .orderByDesc(TicketAttachmentEntity::getCreatedAt));
        if (attachments.isEmpty()) {
            return List.of();
        }
        List<Long> fileIds = attachments.stream().map(TicketAttachmentEntity::getFileId).toList();
        Map<Long, FileObjectEntity> files = fileObjectMapper.selectBatchIds(fileIds).stream()
                .collect(Collectors.toMap(FileObjectEntity::getId, Function.identity()));
        return attachments.stream()
                .filter(attachment -> files.containsKey(attachment.getFileId()))
                .map(attachment -> toView(attachment, files.get(attachment.getFileId())))
                .toList();
    }

    @Transactional
    public void delete(long ticketId, long attachmentId, long userId) {
        requireTicket(ticketId);
        TicketAttachmentEntity attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null || attachment.getDeletedAt() != null
                || !String.valueOf(ticketId).equals(String.valueOf(attachment.getTicketId()))) {
            throw new ApiException(ErrorCode.TICKET_NOT_FOUND, "附件不存在");
        }
        attachment.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        attachment.setDeletedBy(userId);
        attachmentMapper.updateById(attachment);

        FileObjectEntity fileObject = fileObjectMapper.selectById(attachment.getFileId());
        if (fileObject != null) {
            fileObject.setStatus("PENDING_DELETE");
            fileObjectMapper.updateById(fileObject);
        }
        auditPort.record(new AuditEvent(
                userId, null, "TICKET_ATTACHMENT_DELETE", "TICKET", String.valueOf(ticketId),
                Map.of("attachmentId", String.valueOf(attachmentId)), null, "SUCCESS", null));
    }

    private TicketEntity requireTicket(long ticketId) {
        TicketEntity ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || ticket.getDeletedAt() != null) {
            throw new ApiException(ErrorCode.TICKET_NOT_FOUND);
        }
        return ticket;
    }

    private AttachmentView toView(TicketAttachmentEntity attachment, FileObjectEntity file) {
        String url = null;
        try {
            url = storage.presignedGetUrl(file.getObjectKey(), presignExpirySeconds);
        } catch (ApiException ignored) {
            // 预签名失败不影响列表，前端可稍后重试
        }
        return new AttachmentView(
                String.valueOf(attachment.getId()),
                String.valueOf(file.getId()),
                file.getOriginalName(),
                file.getContentType(),
                file.getSizeBytes(),
                String.valueOf(attachment.getCreatedBy()),
                file.getCreatedAt() == null ? null
                        : file.getCreatedAt().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                url
        );
    }

    private String extensionOf(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index + 1).toLowerCase();
    }

    private boolean magicMatches(String extension, byte[] bytes) {
        return switch (extension) {
            case "pdf" -> startsWith(bytes, new byte[]{0x25, 0x50, 0x44, 0x46});
            case "docx" -> startsWith(bytes, new byte[]{0x50, 0x4B, 0x03, 0x04});
            case "png" -> startsWith(bytes, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47,
                    0x0D, 0x0A, 0x1A, 0x0A});
            case "jpg" -> startsWith(bytes, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            case "md" -> true;
            default -> false;
        };
    }

    private boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private String contentTypeOf(String extension, String provided) {
        if (StringUtils.hasText(provided)) {
            return provided;
        }
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "md" -> "text/markdown";
            case "png" -> "image/png";
            case "jpg" -> "image/jpeg";
            default -> "application/octet-stream";
        };
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.SYS_INTERNAL_ERROR);
        }
    }
}
