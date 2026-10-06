package dev.koradb.ai.provider;

/**
 * Universal request to an LLM provider.
 */
public record LlmRequest(
        String systemPrompt,
        String userPrompt,
        double temperature,
        int maxTokens
) {

    public static final double DEFAULT_TEMPERATURE = 0.1;
    public static final int DEFAULT_MAX_TOKENS = 1024;

    public static LlmRequest of(String systemPrompt, String userPrompt) {
        return new LlmRequest(systemPrompt, userPrompt, DEFAULT_TEMPERATURE, DEFAULT_MAX_TOKENS);
    }

    public static LlmRequest of(String userPrompt) {
        return new LlmRequest(null, userPrompt, DEFAULT_TEMPERATURE, DEFAULT_MAX_TOKENS);
    }
}
