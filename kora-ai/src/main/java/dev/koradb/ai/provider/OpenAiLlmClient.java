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
 * LLM client for OpenAI and OpenAI-compatible inference backends
 * (e.g., local Ollama, vLLM, LM Studio, LocalAI).
 */
public final class OpenAiLlmClient implements LlmClient {

    public static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final HttpClient httpClient;
    private final Duration timeout;

    public OpenAiLlmClient(String apiKey) {
        this(apiKey, DEFAULT_ENDPOINT, DEFAULT_MODEL, Duration.ofSeconds(30));
    }

    public OpenAiLlmClient(String apiKey, String endpoint, String model, Duration timeout) {
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
        return "openai";
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long start = System.currentTimeMillis();

        List<Map<String, Object>> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        messages.add(Map.of("role", "user", "content", request.userPrompt()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("temperature", request.temperature());
        payload.put("max_tokens", request.maxTokens());

        String jsonBody = SimpleJson.stringify(payload);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        if (!apiKey.isEmpty()) {
            reqBuilder.header("Authorization", "Bearer " + apiKey);
        }

        try {
            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - start;

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                Map<String, Object> respMap = SimpleJson.parseObject(response.body());
                String content = SimpleJson.getString(respMap, "choices.0.message.content");
                Integer promptTokens = SimpleJson.getInt(respMap, "usage.prompt_tokens");
                Integer completionTokens = SimpleJson.getInt(respMap, "usage.completion_tokens");

                return new LlmResponse(
                        content != null ? content : "",
                        promptTokens != null ? promptTokens : 0,
                        completionTokens != null ? completionTokens : 0,
                        duration,
                        model,
                        getProviderName()
                );
            } else {
                throw new RuntimeException("OpenAI API returned HTTP " + response.statusCode() + ": " + response.body());
            }

        } catch (IOException e) {
            throw new RuntimeException("Network error communicating with OpenAI API: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OpenAI API request interrupted", e);
        }
    }
}
