package dev.koradb.transaction.lock;

/**
 * Thrown when a transaction cannot acquire a lock due to lock contention or potential deadlock timeout.
 */
public class DeadlockException extends RuntimeException {

    public DeadlockException(String message) {
        super(message);
    }

    public DeadlockException(String message, Throwable cause) {
        super(message, cause);
    }
}
