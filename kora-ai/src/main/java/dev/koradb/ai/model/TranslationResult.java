package dev.koradb.ai.model;

/**
 * Result of translating a natural language request into a validated SQL statement.
 */
public record TranslationResult(
        String originalPrompt,
        String generatedSql,
        boolean isValid,
        String errorMessage,
        String provider,
        String model,
        long durationMillis
) {

    public static TranslationResult valid(String prompt, String sql, String provider, String model, long durationMillis) {
        return new TranslationResult(prompt, sql, true, null, provider, model, durationMillis);
    }

    public static TranslationResult invalid(String prompt, String sql, String error, String provider, String model, long durationMillis) {
        return new TranslationResult(prompt, sql, false, error, provider, model, durationMillis);
    }
}
