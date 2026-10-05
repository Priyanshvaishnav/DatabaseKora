package dev.koradb.transaction;

/**
 * ANSI SQL transaction isolation levels.
 */
public enum IsolationLevel {
    /**
     * Dirty reads allowed (lowest isolation).
     */
    READ_UNCOMMITTED,

    /**
     * Prevents dirty reads. Read locks released as soon as read completes or held till end.
     */
    READ_COMMITTED,

    /**
     * Prevents dirty reads and non-repeatable reads.
     */
    REPEATABLE_READ,

    /**
     * Highest isolation: Strict 2PL on all read/write sets, preventing phantoms and anomalies.
     */
    SERIALIZABLE,

    /**
     * Snapshot Isolation: Reads observe a consistent point-in-time snapshot via MVCC without locking.
     */
    SNAPSHOT
}
