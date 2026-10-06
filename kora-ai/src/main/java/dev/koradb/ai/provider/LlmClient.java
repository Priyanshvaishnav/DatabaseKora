package dev.koradb.ai.provider;

import java.util.concurrent.CompletableFuture;

/**
 * Universal interface for Large Language Model providers.
 */
public interface LlmClient extends AutoCloseable {

    /**
     * Name identifier for the provider (e.g. "rule-based", "openai", "gemini", "anthropic").
     */
    String getProviderName();

    /**
     * The model name configured for this provider (e.g. "offline-rules", "gpt-4o-mini", "gemini-1.5-flash").
     */
    String getModelName();

    /**
     * Synchronously completes the given request.
     */
    LlmResponse complete(LlmRequest request);

    /**
     * Asynchronously completes the given request.
     */
    default CompletableFuture<LlmResponse> completeAsync(LlmRequest request) {
        return CompletableFuture.supplyAsync(() -> complete(request));
    }

    @Override
    default void close() throws Exception {
        // Default no-op for stateless or pooled HTTP clients
    }
}
