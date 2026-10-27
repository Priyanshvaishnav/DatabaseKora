package dev.koradb.ai.provider;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Sends a provider request, retrying rate limits (429), temporary server errors (500, 502, 503,
 * 504) and network errors with exponential backoff. {@code Retry-After} is honoured up to
 * {@link #MAX_WAIT_MILLIS}. The final response is returned whatever its status.
 */
final class HttpRetry {

    static final int MAX_ATTEMPTS = 3;
    static final long BASE_DELAY_MILLIS = 500;
    static final long MAX_WAIT_MILLIS = 10_000;

    private HttpRetry() {}

    static HttpResponse<String> send(HttpClient client, HttpRequest request, String provider) {
        IOException lastError = null;
        for (int attempt = 1; ; attempt++) {
            HttpResponse<String> response = null;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (!isRetryable(response.statusCode()) || attempt >= MAX_ATTEMPTS) {
                    return response;
                }
            } catch (IOException e) {
                lastError = e;
                // Only a refused/failed connection is retried; a timeout already used the caller's
                // whole time budget, so retrying it would multiply the wait.
                if (attempt >= MAX_ATTEMPTS || !(e instanceof java.net.ConnectException)) {
                    throw new LlmProviderException("Network error communicating with " + provider + " API: " + e.getMessage(), 0, e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmProviderException(provider + " API request interrupted", 0, e);
            }
            sleep(delayMillis(response, attempt), provider);
        }
    }

    static boolean isRetryable(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    static long delayMillis(HttpResponse<String> response, int attempt) {
        long backoff = BASE_DELAY_MILLIS << (attempt - 1);
        if (response != null) {
            String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
            if (retryAfter != null) {
                try {
                    return Math.min(MAX_WAIT_MILLIS, Math.max(0, Long.parseLong(retryAfter.trim()) * 1000));
                } catch (NumberFormatException ignored) {
                    // HTTP-date form: fall back to backoff
                }
            }
        }
        return Math.min(MAX_WAIT_MILLIS, backoff);
    }

    private static void sleep(long millis, String provider) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmProviderException(provider + " API request interrupted", 0, e);
        }
    }
}
