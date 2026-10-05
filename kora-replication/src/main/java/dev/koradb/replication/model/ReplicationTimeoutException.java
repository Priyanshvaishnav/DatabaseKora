package dev.koradb.replication.model;

/**
 * Exception thrown when a synchronous replication acknowledgment times out.
 */
public class ReplicationTimeoutException extends RuntimeException {

    public ReplicationTimeoutException(String message) {
        super(message);
    }

    public ReplicationTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
