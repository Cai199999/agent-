package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.FileObjectEntity;
import com.agentgongdan.adapters.persistence.entity.KbChunkEntity;
import com.agentgongdan.adapters.persistence.entity.KbDocEntity;
import com.agentgongdan.adapters.persistence.entity.VectorObjectEntity;
import com.agentgongdan.adapters.persistence.mapper.BizSequenceMapper;
import com.agentgongdan.adapters.persistence.mapper.FileObjectMapper;
import com.agentgongdan.adapters.persistence.mapper.KbChunkMapper;
import com.agentgongdan.adapters.persistence.mapper.KbDocMapper;
import com.agentgongdan.adapters.persistence.mapper.VectorObjectMapper;
import com.agentgongdan.adapters.web.dto.KnowledgeDocView;
import com.agentgongdan.adapters.web.dto.KnowledgeDownloadView;
import com.agentgongdan.adapters.web.dto.KnowledgeHitView;
import com.agentgongdan.adapters.web.dto.KnowledgeSearchRequest;
import com.agentgongdan.adapters.web.dto.PageResult;
import com.agentgongdan.app.ports.ai.AiAvailabilityPort;
import com.agentgongdan.app.ports.audit.AuditPort;
import com.agentgongdan.app.ports.storage.ObjectStoragePort;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.agentgongdan.domain.audit.AuditEvent;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class KnowledgeService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("md", "txt");

    private final KbDocMapper docMapper;
    private final KbChunkMapper chunkMapper;
    private final VectorObjectMapper vectorObjectMapper;
    private final FileObjectMapper fileObjectMapper;
    private final BizSequenceMapper sequenceMapper;
    private final ObjectStoragePort storage;
    private final AuditPort auditPort;
    private final AiAvailabilityPort availability;
    private final RestClient pythonRestClient;
    private final String embeddingModel;
    private final int embeddingDim;
    private final String vectorCollection;
    private final String appEnv;
    private final long maxSizeMb;
    private final int presignExpirySeconds;

    public KnowledgeService(KbDocMapper docMapper,
                            KbChunkMapper chunkMapper,
                            VectorObjectMapper vectorObjectMapper,
                            FileObjectMapper fileObjectMapper,
                            BizSequenceMapper sequenceMapper,
                            ObjectStoragePort storage,
                            AuditPort auditPort,
                            AiAvailabilityPort availability,
                            @Qualifier("pythonRestClient") RestClient pythonRestClient,
                            @Value("${EMBEDDING_MODEL:BAAI/bge-m3}") String embeddingModel,
                            @Value("${EMBEDDING_DIM:1024}") int embeddingDim,
                            @Value("${MILVUS_COLLECTION_KNOWLEDGE:agent_gongdan_kb_bge_m3_1024_v1}") String vectorCollection,
                            @Value("${APP_ENV:dev}") String appEnv,
                            @Value("${UPLOAD_MAX_SIZE_MB:10}") long maxSizeMb,
                            @Value("${MINIO_PRESIGN_EXPIRE_SECONDS:600}") int presignExpirySeconds) {
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.vectorObjectMapper = vectorObjectMapper;
        this.fileObjectMapper = fileObjectMapper;
        this.sequenceMapper = sequenceMapper;
        this.storage = storage;
        this.auditPort = auditPort;
        this.availability = availability;
        this.pythonRestClient = pythonRestClient;
        this.embeddingModel = embeddingModel;
        this.embeddingDim = embeddingDim;
        this.vectorCollection = vectorCollection;
        this.appEnv = appEnv;
        this.maxSizeMb = maxSizeMb;
        this.presignExpirySeconds = presignExpirySeconds;
    }

    @Transactional
    public KnowledgeDocView upload(MultipartFile file, String title, String categoryCode, long userId) {
        if (!StringUtils.hasText(title)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "知识标题不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "知识文件不能为空");
        }
        if (file.getSize() > maxSizeMb * 1024 * 1024) {
            throw new ApiException(ErrorCode.VALIDATION_FILE_SIZE);
        }
        String originalName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "knowledge.md";
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ApiException(ErrorCode.VALIDATION_FILE_TYPE, "当前知识库支持 md/txt");
        }

        byte[] bytes;
        String text;
        try {
            bytes = file.getBytes();
            text = new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "读取知识文件失败");
        }
        if (!StringUtils.hasText(text)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "知识文件内容为空");
        }

        long docId = IdWorker.getId();
        String docNo = nextDocNo();
        int docVersion = 1;
        String category = StringUtils.hasText(categoryCode) ? categoryCode : "GENERAL";
        String checksum = sha256(bytes);
        String objectKey = "agent-gongdan/" + appEnv + "/KB_DOC/"
                + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy/MM"))
                + "/" + UUID.randomUUID() + "." + extension;
        String contentType = "md".equals(extension) ? "text/markdown" : "text/plain";
        storage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, contentType);

        try {
            Map<String, Object> payload = Map.of(
                    "doc_id", docId,
                    "doc_version", docVersion,
                    "title", title,
                    "category_code", category,
                    "visibility_type", "PUBLIC",
                    "text", text
            );
            JsonNode response = pythonRestClient.post()
                    .uri("/v1/knowledge/ingest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || response.path("chunks").isEmpty()) {
                throw new ApiException(ErrorCode.KB_INGEST_FAILED);
            }

            long fileId = IdWorker.getId();
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
            fileObject.setBizType("KB_DOC");
            fileObject.setBizId(docId);
            fileObject.setStatus("ACTIVE");
            fileObject.setUploadedBy(userId);
            fileObjectMapper.insert(fileObject);

            KbDocEntity doc = new KbDocEntity();
            doc.setId(docId);
            doc.setTenantId(0L);
            doc.setDocNo(docNo);
            doc.setTitle(title);
            doc.setCategoryCode(category);
            doc.setSourceType("UPLOAD");
            doc.setFileId(fileId);
            doc.setVisibilityType("PUBLIC");
            doc.setStatus("ACTIVE");
            doc.setCurrentVersion(docVersion);
            doc.setChunkCount(0);
            doc.setEmbeddingModel(embeddingModel);
            doc.setEmbeddingDim(embeddingDim);
            doc.setVectorCollection(vectorCollection);
            doc.setIndexVersion(docVersion);
            doc.setChecksum(checksum);
            doc.setVersion(0);
            doc.setCreatedBy(userId);
            docMapper.insert(doc);

            int chunkCount = 0;
            for (JsonNode chunkNode : response.path("chunks")) {
                long chunkId = IdWorker.getId();
                int chunkNo = chunkNode.path("chunk_no").asInt();
                String vectorId = chunkNode.path("vector_id").asText();
                String content = chunkNode.path("content").asText();
                String contentHash = chunkNode.path("content_hash").asText();

                KbChunkEntity chunk = new KbChunkEntity();
                chunk.setId(chunkId);
                chunk.setTenantId(0L);
                chunk.setDocId(docId);
                chunk.setDocVersion(docVersion);
                chunk.setChunkNo(chunkNo);
                chunk.setContent(content);
                chunk.setVectorId(vectorId);
                chunk.setVectorCollection(vectorCollection);
                chunk.setStatus("ACTIVE");
                chunk.setCreatedBy(userId);
                chunkMapper.insert(chunk);

                VectorObjectEntity vectorObject = new VectorObjectEntity();
                vectorObject.setId(IdWorker.getId());
                vectorObject.setTenantId(0L);
                vectorObject.setObjectType("KB_CHUNK");
                vectorObject.setObjectId(chunkId);
                vectorObject.setObjectVersion(docVersion);
                vectorObject.setSeqNo(chunkNo);
                vectorObject.setVectorId(vectorId);
                vectorObject.setVectorCollection(vectorCollection);
                vectorObject.setContentHash(contentHash);
                vectorObject.setStatus("ACTIVE");
                vectorObject.setCreatedBy(userId);
                vectorObjectMapper.insert(vectorObject);
                chunkCount++;
            }
            doc.setChunkCount(chunkCount);
            docMapper.updateById(doc);

            auditPort.record(new AuditEvent(
                    userId, null, "KB_INGEST", "KB_DOC", String.valueOf(docId),
                    null, Map.of("docNo", docNo, "chunkCount", chunkCount), "SUCCESS", null));
            return toDocView(docMapper.selectById(docId));
        } catch (ApiException e) {
            storage.remove(objectKey);
            throw e;
        } catch (Exception e) {
            storage.remove(objectKey);
            throw new ApiException(ErrorCode.KB_INGEST_FAILED);
        }
    }

    public PageResult<KnowledgeDocView> list(long pageNo, long pageSize) {
        Page<KbDocEntity> page = new Page<>(pageNo, pageSize);
        Page<KbDocEntity> result = docMapper.selectPage(page, Wrappers.<KbDocEntity>lambdaQuery()
                .eq(KbDocEntity::getTenantId, 0L)
                .isNull(KbDocEntity::getDeletedAt)
                .orderByDesc(KbDocEntity::getCreatedAt));
        List<KnowledgeDocView> items = result.getRecords().stream().map(this::toDocView).toList();
        return new PageResult<>(items, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public KnowledgeDownloadView download(long docId) {
        KbDocEntity doc = requireDoc(docId);
        if (doc.getFileId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "该知识没有可下载的原始文件");
        }
        FileObjectEntity file = fileObjectMapper.selectById(doc.getFileId());
        if (file == null || file.getDeletedAt() != null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "原始文件不存在或已删除");
        }
        String url = storage.presignedGetUrl(file.getObjectKey(), presignExpirySeconds);
        return new KnowledgeDownloadView(url, file.getOriginalName(), presignExpirySeconds);
    }

    @Transactional
    public void delete(long docId, long userId) {
        KbDocEntity doc = docMapper.selectById(docId);
        if (doc == null || doc.getDeletedAt() != null) {
            return;
        }
        List<KbChunkEntity> chunks = chunkMapper.selectList(Wrappers.<KbChunkEntity>lambdaQuery()
                .eq(KbChunkEntity::getDocId, docId)
                .isNull(KbChunkEntity::getDeletedAt));
        List<String> vectorIds = chunks.stream().map(KbChunkEntity::getVectorId).toList();
        List<Long> chunkIds = chunks.stream().map(KbChunkEntity::getId).toList();
        if (!vectorIds.isEmpty()) {
            try {
                pythonRestClient.post()
                        .uri("/v1/vector/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("object_type", "KB_CHUNK", "vector_ids", vectorIds))
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                // 向量删除失败不阻断元数据删除；由对账任务兜底。
            }
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (!chunkIds.isEmpty()) {
            chunkMapper.update(null, Wrappers.<KbChunkEntity>lambdaUpdate()
                    .eq(KbChunkEntity::getDocId, docId)
                    .isNull(KbChunkEntity::getDeletedAt)
                    .set(KbChunkEntity::getDeletedAt, now)
                    .set(KbChunkEntity::getDeletedBy, userId));
            vectorObjectMapper.update(null, Wrappers.<VectorObjectEntity>lambdaUpdate()
                    .eq(VectorObjectEntity::getObjectType, "KB_CHUNK")
                    .in(VectorObjectEntity::getObjectId, chunkIds)
                    .set(VectorObjectEntity::getStatus, "DELETED"));
        }
        docMapper.update(null, Wrappers.<KbDocEntity>lambdaUpdate()
                .eq(KbDocEntity::getId, docId)
                .isNull(KbDocEntity::getDeletedAt)
                .set(KbDocEntity::getDeletedAt, now)
                .set(KbDocEntity::getDeletedBy, userId)
                .set(KbDocEntity::getStatus, "DISABLED"));
        auditPort.record(new AuditEvent(
                userId, null, "KB_DELETE", "KB_DOC", String.valueOf(docId),
                Map.of("docNo", doc.getDocNo(), "chunkCount", chunks.size()), null, "SUCCESS", null));
    }

    private KbDocEntity requireDoc(long docId) {
        KbDocEntity doc = docMapper.selectById(docId);
        if (doc == null || doc.getDeletedAt() != null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "知识文档不存在或已删除");
        }
        return doc;
    }

    public List<KnowledgeHitView> search(KnowledgeSearchRequest request) {
        if (!StringUtils.hasText(request.query())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "查询内容不能为空");
        }
        int topK = request.topK() == null ? 5 : Math.min(Math.max(request.topK(), 1), 20);
        Map<String, Object> payload = new HashMap<>();
        payload.put("query", request.query());
        payload.put("top_k", topK);
        if (StringUtils.hasText(request.categoryCode())) {
            payload.put("category_code", request.categoryCode());
        }

        if (!availability.allow(AiAvailabilityPort.SCOPE_RETRIEVAL)) {
            throw new ApiException(ErrorCode.KB_VECTOR_STORE_ERROR);
        }
        JsonNode response;
        try {
            response = pythonRestClient.post()
                    .uri("/v1/knowledge/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            availability.failure(AiAvailabilityPort.SCOPE_RETRIEVAL);
            throw new ApiException(ErrorCode.KB_VECTOR_STORE_ERROR);
        }
        if (response == null) {
            availability.failure(AiAvailabilityPort.SCOPE_RETRIEVAL);
            throw new ApiException(ErrorCode.KB_VECTOR_STORE_ERROR);
        }
        availability.success(AiAvailabilityPort.SCOPE_RETRIEVAL);

        List<String> vectorIds = new ArrayList<>();
        List<Long> docIds = new ArrayList<>();
        List<JsonNode> hitNodes = new ArrayList<>();
        for (JsonNode hit : response.path("hits")) {
            hitNodes.add(hit);
            vectorIds.add(hit.path("vector_id").asText());
            docIds.add(hit.path("doc_id").asLong());
        }
        if (hitNodes.isEmpty()) {
            return List.of();
        }

        Map<String, KbChunkEntity> chunks = chunkMapper.selectList(
                        Wrappers.<KbChunkEntity>lambdaQuery()
                                .in(KbChunkEntity::getVectorId, vectorIds))
                .stream()
                .collect(Collectors.toMap(KbChunkEntity::getVectorId, Function.identity(),
                        (first, second) -> first));
        Map<Long, KbDocEntity> docs = docMapper.selectBatchIds(docIds.stream().distinct().toList())
                .stream()
                .collect(Collectors.toMap(KbDocEntity::getId, Function.identity()));

        List<KnowledgeHitView> views = new ArrayList<>();
        for (JsonNode hit : hitNodes) {
            String vectorId = hit.path("vector_id").asText();
            KbChunkEntity chunk = chunks.get(vectorId);
            KbDocEntity doc = docs.get(hit.path("doc_id").asLong());
            if (chunk == null || doc == null) {
                continue;
            }
            views.add(new KnowledgeHitView(
                    String.valueOf(doc.getId()),
                    doc.getDocNo(),
                    doc.getTitle(),
                    String.valueOf(chunk.getId()),
                    chunk.getChunkNo(),
                    vectorId,
                    hit.path("score").asDouble(),
                    chunk.getContent()
            ));
        }
        return views;
    }

    private String nextDocNo() {
        sequenceMapper.ensure(IdWorker.getId(), "KB_DOC_NO");
        sequenceMapper.next("KB_DOC_NO");
        long value = sequenceMapper.lastValue();
        String date = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        return "KB" + date + String.format("%06d", value);
    }

    private String extensionOf(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index + 1).toLowerCase();
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.SYS_INTERNAL_ERROR);
        }
    }

    private KnowledgeDocView toDocView(KbDocEntity doc) {
        return new KnowledgeDocView(
                String.valueOf(doc.getId()),
                doc.getDocNo(),
                doc.getTitle(),
                doc.getCategoryCode(),
                doc.getStatus(),
                doc.getChunkCount(),
                doc.getEmbeddingModel(),
                doc.getEmbeddingDim(),
                doc.getCreatedAt() == null ? null
                        : doc.getCreatedAt().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );
    }
}
