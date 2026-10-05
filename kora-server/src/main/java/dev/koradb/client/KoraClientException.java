package dev.koradb.client;

/**
 * Exception thrown when a client-side execution error or server-reported error occurs.
 */
public class KoraClientException extends RuntimeException {

    private final int errorCode;

    public KoraClientException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public KoraClientException(String message) {
        this(1000, message);
    }

    public KoraClientException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = 1000;
    }

    public int getErrorCode() {
        return errorCode;
    }
}
