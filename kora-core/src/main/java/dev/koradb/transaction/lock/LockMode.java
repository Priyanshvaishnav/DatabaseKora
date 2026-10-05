package dev.koradb.transaction.lock;

/**
 * Lock modes for Strict Two-Phase Locking (SS2PL).
 */
public enum LockMode {
    /**
     * Shared lock for read operations (S-lock).
     * Multiple transactions may hold a shared lock concurrently on the same resource.
     */
    SHARED,

    /**
     * Exclusive lock for write operations (X-lock).
     * Only a single transaction may hold an exclusive lock, with mutual exclusion
     * against both shared and exclusive locks.
     */
    EXCLUSIVE
}
