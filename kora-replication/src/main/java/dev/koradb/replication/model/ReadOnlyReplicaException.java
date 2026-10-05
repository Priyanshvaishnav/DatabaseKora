package dev.koradb.replication.model;

/**
 * Exception thrown when a write/mutation statement is executed against a read-only Replica.
 */
public class ReadOnlyReplicaException extends RuntimeException {

    public ReadOnlyReplicaException(String message) {
        super(message);
    }
}
