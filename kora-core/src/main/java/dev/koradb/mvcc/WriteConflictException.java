package dev.koradb.mvcc;

import dev.koradb.exception.ExecutionException;

/**
 * Thrown when two concurrent transactions attempt to modify the same row version,
 * violating the first-committer-wins rule under Multi-Version Concurrency Control (MVCC).
 */
public class WriteConflictException extends ExecutionException {

    private final long targetXmax;
    private final long currentTxnId;

    public WriteConflictException(String message) {
        super(message);
        this.targetXmax = 0L;
        this.currentTxnId = 0L;
    }

    public WriteConflictException(long targetXmax, long currentTxnId) {
        super("Write conflict: row is concurrently modified or deleted by active transaction " +
                targetXmax + " (attempted by txn " + currentTxnId + ")");
        this.targetXmax = targetXmax;
        this.currentTxnId = currentTxnId;
    }

    public long getTargetXmax() {
        return targetXmax;
    }

    public long getCurrentTxnId() {
        return currentTxnId;
    }
}
