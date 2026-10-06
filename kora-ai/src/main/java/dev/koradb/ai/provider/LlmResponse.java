package dev.koradb.ai.provider;

/**
 * Universal response returned from an LLM provider.
 */
public record LlmResponse(
        String content,
        int promptTokens,
        int completionTokens,
        long durationMillis,
        String model,
        String provider
) {

    public static LlmResponse of(String content, String model, String provider) {
        return new LlmResponse(content, 0, 0, 0L, model, provider);
    }

    public static LlmResponse of(String content, String model, String provider, long durationMillis) {
        return new LlmResponse(content, 0, 0, durationMillis, model, provider);
    }
}
