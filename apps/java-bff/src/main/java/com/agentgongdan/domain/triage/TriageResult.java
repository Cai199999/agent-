package com.agentgongdan.domain.triage;

import java.util.Map;

/**
 * 一次分诊的规范化结果（已由 Python 侧校验与限幅）。
 */
public record TriageResult(
        String intentCode,
        Map<String, Object> entities,
        double confidence,
        String action,
        boolean lowConfidence,
        Map<String, Object> draft,
        String model,
        String promptVersion,
        long latencyMs
) {
}
