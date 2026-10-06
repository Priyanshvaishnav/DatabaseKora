package dev.koradb.ai.model;

import java.util.List;

/**
 * Report detailing performance analysis, missing index opportunities, and optimization DDLs.
 */
public record OptimizationReport(
        String sql,
        String analysis,
        List<String> recommendedIndexDdls,
        String provider,
        String model,
        long durationMillis
) {

    public OptimizationReport {
        recommendedIndexDdls = recommendedIndexDdls != null ? List.copyOf(recommendedIndexDdls) : List.of();
    }
}
