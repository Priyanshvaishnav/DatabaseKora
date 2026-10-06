package dev.koradb.ai.model;

/**
 * Result of explaining a SQL query in natural language.
 */
public record ExplanationResult(
        String sql,
        String explanation,
        String provider,
        String model,
        long durationMillis
) {}
