package dev.koradb.storage.recovery;

/**
 * Status of a transaction during recovery analysis.
 */
public enum TransactionStatus {
    ACTIVE,
    COMMITTED,
    ABORTED
}
