package dev.koradb.replication.protocol;

/**
 * Exception thrown when a replication protocol error, corruption, or version mismatch occurs.
 */
public class ReplicationProtocolException extends RuntimeException {

    public ReplicationProtocolException(String message) {
        super(message);
    }

    public ReplicationProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
