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
 * LLM client connecting to Google AI Studio Gemini API (e.g. gemini-1.5-flash, gemini-1.5-pro).
 */
public final class GeminiLlmClient implements LlmClient {

    public static final String DEFAULT_MODEL = "gemini-1.5-flash";
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final Duration timeout;

    public GeminiLlmClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, Duration.ofSeconds(30));
    }

    public GeminiLlmClient(String apiKey, String model, Duration timeout) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.model = (model != null && !model.isBlank()) ? model.trim() : DEFAULT_MODEL;
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    @Override
    public String getProviderName() {
        return "gemini";
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long start = System.currentTimeMillis();

        Map<String, Object> payload = new LinkedHashMap<>();

        // System prompt instruction
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            payload.put("system_instruction", Map.of(
                    "parts", List.of(Map.of("text", request.systemPrompt()))
            ));
        }

        // Contents
        payload.put("contents", List.of(
                Map.of("parts", List.of(Map.of("text", request.userPrompt())))
        ));

        // Generation config
        payload.put("generationConfig", Map.of(
                "temperature", request.temperature(),
                "maxOutputTokens", request.maxTokens()
        ));

        String jsonBody = SimpleJson.stringify(payload);
        String url = BASE_URL + model + ":generateContent?key=" + apiKey;

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        try {
            HttpResponse<String> response = HttpRetry.send(httpClient, httpRequest, "Gemini");
            long duration = System.currentTimeMillis() - start;

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                Map<String, Object> respMap = SimpleJson.parseObject(response.body());
                String content = SimpleJson.getString(respMap, "candidates.0.content.parts.0.text");
                Integer promptTokens = SimpleJson.getInt(respMap, "usageMetadata.promptTokenCount");
                Integer completionTokens = SimpleJson.getInt(respMap, "usageMetadata.candidatesTokenCount");

                return new LlmResponse(
                        content != null ? content : "",
                        promptTokens != null ? promptTokens : 0,
                        completionTokens != null ? completionTokens : 0,
                        duration,
                        model,
                        getProviderName()
                );
            } else {
                throw new LlmProviderException("Gemini API returned HTTP " + response.statusCode() + ": " + response.body(), response.statusCode());
            }

        } catch (LlmProviderException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmProviderException("Unreadable response from Gemini API: " + e.getMessage(), 0, e);
        }
    }
}
