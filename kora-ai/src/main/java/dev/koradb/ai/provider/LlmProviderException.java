package dev.koradb.ai.provider;

/**
 * An LLM provider call failed: an HTTP error status (statusCode &gt; 0), a network error or an
 * unreadable response (statusCode 0).
 */
public class LlmProviderException extends RuntimeException {

    private final int statusCode;

    public LlmProviderException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public LlmProviderException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    /** HTTP status returned by the provider, or 0 when there was no HTTP response. */
    public int getStatusCode() {
        return statusCode;
    }

    /** True for rate limiting (429). */
    public boolean isRateLimited() {
        return statusCode == 429;
    }
}
