package dev.koradb.protocol;

/**
 * Exception thrown when a protocol violation, bad framing, or decoding error occurs.
 */
public class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
