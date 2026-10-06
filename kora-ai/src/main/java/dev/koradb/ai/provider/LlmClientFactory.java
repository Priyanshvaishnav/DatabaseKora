package dev.koradb.ai.provider;

import java.time.Duration;

/**
 * Factory for creating and discovering active {@link LlmClient} instances
 * based on environment variables and explicit configuration.
 */
public final class LlmClientFactory {

    private LlmClientFactory() {}

    /**
     * Auto-discovers and creates the best available LLM client:
     * <ol>
     *   <li>Checks {@code KORA_AI_PROVIDER} explicitly if set.</li>
     *   <li>If {@code OPENAI_API_KEY} is set &rarr; {@link OpenAiLlmClient}.</li>
     *   <li>If {@code GEMINI_API_KEY} is set &rarr; {@link GeminiLlmClient}.</li>
     *   <li>If {@code ANTHROPIC_API_KEY} is set &rarr; {@link AnthropicLlmClient}.</li>
     *   <li>Defaults to offline {@link RuleBasedLlmClient} (zero configuration needed).</li>
     * </ol>
     */
    public static LlmClient createDefault() {
        String explicitProvider = System.getenv("KORA_AI_PROVIDER");
        if (explicitProvider != null && !explicitProvider.isBlank()) {
            return createByName(explicitProvider.trim().toLowerCase());
        }

        String openAiKey = System.getenv("OPENAI_API_KEY");
        if (openAiKey != null && !openAiKey.isBlank()) {
            String endpoint = System.getenv("OPENAI_BASE_URL");
            String model = System.getenv("OPENAI_MODEL");
            return new OpenAiLlmClient(openAiKey, endpoint, model, Duration.ofSeconds(30));
        }

        String geminiKey = System.getenv("GEMINI_API_KEY");
        if (geminiKey != null && !geminiKey.isBlank()) {
            String model = System.getenv("GEMINI_MODEL");
            return new GeminiLlmClient(geminiKey, model, Duration.ofSeconds(30));
        }

        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        if (anthropicKey != null && !anthropicKey.isBlank()) {
            String model = System.getenv("ANTHROPIC_MODEL");
            return new AnthropicLlmClient(anthropicKey, null, model, Duration.ofSeconds(30));
        }

        return new RuleBasedLlmClient();
    }

    /**
     * Creates an LLM client by provider name identifier.
     */
    public static LlmClient createByName(String name) {
        if (name == null) return new RuleBasedLlmClient();

        return switch (name.toLowerCase()) {
            case "openai" -> {
                String key = System.getenv().getOrDefault("OPENAI_API_KEY", "");
                String endpoint = System.getenv("OPENAI_BASE_URL");
                String model = System.getenv("OPENAI_MODEL");
                yield new OpenAiLlmClient(key, endpoint, model, Duration.ofSeconds(30));
            }
            case "gemini" -> {
                String key = System.getenv().getOrDefault("GEMINI_API_KEY", "");
                String model = System.getenv("GEMINI_MODEL");
                yield new GeminiLlmClient(key, model, Duration.ofSeconds(30));
            }
            case "anthropic" -> {
                String key = System.getenv().getOrDefault("ANTHROPIC_API_KEY", "");
                String model = System.getenv("ANTHROPIC_MODEL");
                yield new AnthropicLlmClient(key, null, model, Duration.ofSeconds(30));
            }
            case "rule-based", "rules", "offline" -> new RuleBasedLlmClient();
            default -> throw new IllegalArgumentException("Unknown AI provider: " + name + ". Supported: rule-based, openai, gemini, anthropic");
        };
    }
}
