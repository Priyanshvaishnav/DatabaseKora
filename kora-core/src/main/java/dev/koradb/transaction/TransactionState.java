package dev.koradb.transaction;

/**
 * Lifecycle states of a database transaction.
 */
public enum TransactionState {
    /**
     * Transaction is currently in progress and accepting operations.
     */
    ACTIVE,

    /**
     * Transaction has committed successfully and its changes are final.
     */
    COMMITTED,

    /**
     * Transaction has aborted and all uncommitted changes were rolled back.
     */
    ABORTED
}
