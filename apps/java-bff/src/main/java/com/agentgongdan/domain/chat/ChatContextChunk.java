package com.agentgongdan.domain.chat;

/**
 * 已由 Java 侧完成召回与内容补全的知识片段，供 Python 组装提示词。
 */
public record ChatContextChunk(
        long docId,
        long chunkId,
        String title,
        String content,
        double score,
        int rank
) {
}
