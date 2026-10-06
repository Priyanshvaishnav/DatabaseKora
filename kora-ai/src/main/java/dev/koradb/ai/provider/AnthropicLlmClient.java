package dev.koradb.ai.provider;

import dev.koradb.ai.util.SimpleJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * LLM client connecting to Anthropic Claude Messages API (e.g. claude-3-5-sonnet, claude-3-haiku).
 */
public final class AnthropicLlmClient implements LlmClient {

    public static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";
    public static final String DEFAULT_MODEL = "claude-3-5-sonnet-20241022";
    public static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final HttpClient httpClient;
    private final Duration timeout;

    public AnthropicLlmClient(String apiKey) {
        this(apiKey, DEFAULT_ENDPOINT, DEFAULT_MODEL, Duration.ofSeconds(30));
    }

    public AnthropicLlmClient(String apiKey, String endpoint, String model, Duration timeout) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.endpoint = (endpoint != null && !endpoint.isBlank()) ? endpoint.trim() : DEFAULT_ENDPOINT;
        this.model = (model != null && !model.isBlank()) ? model.trim() : DEFAULT_MODEL;
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    @Override
    public String getProviderName() {
        return "anthropic";
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long start = System.currentTimeMillis();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("max_tokens", request.maxTokens());
        payload.put("temperature", request.temperature());

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            payload.put("system", request.systemPrompt());
        }

        payload.put("messages", List.of(
                Map.of("role", "user", "content", request.userPrompt())
        ));

        String jsonBody = SimpleJson.stringify(payload);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - start;

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                Map<String, Object> respMap = SimpleJson.parseObject(response.body());
                String content = SimpleJson.getString(respMap, "content.0.text");
                Integer inputTokens = SimpleJson.getInt(respMap, "usage.input_tokens");
                Integer outputTokens = SimpleJson.getInt(respMap, "usage.output_tokens");

                return new LlmResponse(
                        content != null ? content : "",
                        inputTokens != null ? inputTokens : 0,
                        outputTokens != null ? outputTokens : 0,
                        duration,
                        model,
                        getProviderName()
                );
            } else {
                throw new RuntimeException("Anthropic API returned HTTP " + response.statusCode() + ": " + response.body());
            }

        } catch (IOException e) {
            throw new RuntimeException("Network error communicating with Anthropic API: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Anthropic API request interrupted", e);
        }
    }
}
