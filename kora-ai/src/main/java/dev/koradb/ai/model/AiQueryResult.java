package dev.koradb.ai.model;

import dev.koradb.execution.QueryResult;

/**
 * Result of executing a natural language query with end-to-end translation and execution.
 */
public record AiQueryResult(
        String originalPrompt,
        String generatedSql,
        QueryResult queryResult,
        boolean executed,
        String errorMessage,
        long durationMillis
) {

    public static AiQueryResult success(String prompt, String sql, QueryResult result, long duration) {
        return new AiQueryResult(prompt, sql, result, true, null, duration);
    }

    public static AiQueryResult failure(String prompt, String sql, String error, long duration) {
        return new AiQueryResult(prompt, sql, null, false, error, duration);
    }
}
