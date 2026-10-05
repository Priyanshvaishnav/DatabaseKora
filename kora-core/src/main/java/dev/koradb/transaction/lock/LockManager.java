package dev.koradb.transaction.lock;

import dev.koradb.transaction.TransactionId;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Strict Two-Phase Locking (SS2PL) Lock Manager.
 *
 * <p>Supports SHARED (read) and EXCLUSIVE (write) locks on resources (tables or records).
 * Multiple transactions can hold SHARED locks on the same resource concurrently.
 * An EXCLUSIVE lock requires complete mutual exclusion.
 *
 * <p>Locks are held until the end of the transaction and released atomically via
 * {@link #releaseAll(TransactionId)}. Deadlocks and excessive wait times are broken
 * via configurable lock acquisition timeouts, throwing {@link DeadlockException}.
 */
public class LockManager {

    public static final long DEFAULT_TIMEOUT_MS = 2000L;

    private final ConcurrentMap<LockResource, ResourceLock> locks = new ConcurrentHashMap<>();
    private final ConcurrentMap<TransactionId, Set<LockResource>> txnLocks = new ConcurrentHashMap<>();
    private volatile long defaultTimeoutMs = DEFAULT_TIMEOUT_MS;

    public LockManager() {
    }

    public LockManager(long defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(long defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    /**
     * Acquires a lock on a resource in the requested mode using default timeout.
     */
    public void acquire(TransactionId txnId, LockResource resource, LockMode mode) {
        acquire(txnId, resource, mode, defaultTimeoutMs);
    }

    /**
     * Acquires a shared lock on a resource using default timeout.
     */
    public void acquireShared(TransactionId txnId, LockResource resource) {
        acquire(txnId, resource, LockMode.SHARED, defaultTimeoutMs);
    }

    /**
     * Acquires a shared lock on a resource with an explicit timeout.
     */
    public void acquireShared(TransactionId txnId, LockResource resource, long timeoutMs) {
        acquire(txnId, resource, LockMode.SHARED, timeoutMs);
    }

    /**
     * Acquires an exclusive lock on a resource using default timeout.
     */
    public void acquireExclusive(TransactionId txnId, LockResource resource) {
        acquire(txnId, resource, LockMode.EXCLUSIVE, defaultTimeoutMs);
    }

    /**
     * Acquires an exclusive lock on a resource with an explicit timeout.
     */
    public void acquireExclusive(TransactionId txnId, LockResource resource, long timeoutMs) {
        acquire(txnId, resource, LockMode.EXCLUSIVE, timeoutMs);
    }

    /**
     * Acquires a lock on the given resource with an explicit timeout.
     *
     * @param txnId     transaction requesting the lock
     * @param resource  resource to lock
     * @param mode      SHARED or EXCLUSIVE
     * @param timeoutMs maximum wait time in milliseconds
     * @throws DeadlockException if lock cannot be acquired within the timeout or if interrupted
     */
    public void acquire(TransactionId txnId, LockResource resource, LockMode mode, long timeoutMs) {
        Objects.requireNonNull(txnId, "txnId must not be null");
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(mode, "mode must not be null");

        ResourceLock lock = locks.computeIfAbsent(resource, k -> new ResourceLock(resource));
        lock.acquire(txnId, mode, timeoutMs);

        // Record resource in transaction lock set
        txnLocks.computeIfAbsent(txnId, k -> ConcurrentHashMap.newKeySet()).add(resource);
    }

    /**
     * Releases all locks held by the given transaction.
     *
     * @param txnId transaction completing (commit or abort)
     */
    public void releaseAll(TransactionId txnId) {
        Objects.requireNonNull(txnId, "txnId must not be null");

        Set<LockResource> resources = txnLocks.remove(txnId);
        if (resources == null || resources.isEmpty()) {
            return;
        }

        for (LockResource res : resources) {
            ResourceLock lock = locks.get(res);
            if (lock != null) {
                lock.release(txnId);
            }
        }
    }

    /**
     * Checks if a resource is currently locked by any transaction.
     */
    public boolean isLocked(LockResource resource) {
        ResourceLock lock = locks.get(resource);
        return lock != null && lock.isLocked();
    }

    /**
     * Gets the lock mode held by a specific transaction on a resource, if any.
     */
    public Optional<LockMode> getLockMode(TransactionId txnId, LockResource resource) {
        ResourceLock lock = locks.get(resource);
        if (lock == null) {
            return Optional.empty();
        }
        return lock.getLockMode(txnId);
    }

    /**
     * Returns the count of resources locked by the given transaction.
     */
    public int getLockCount(TransactionId txnId) {
        Set<LockResource> set = txnLocks.get(txnId);
        return set != null ? set.size() : 0;
    }

    /**
     * Internal state for a single resource's lock.
     */
    private static class ResourceLock {
        private final LockResource resource;
        private final Set<TransactionId> sharedHolders = new HashSet<>();
        private TransactionId exclusiveHolder = null;

        ResourceLock(LockResource resource) {
            this.resource = resource;
        }

        synchronized void acquire(TransactionId txnId, LockMode mode, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;

            if (mode == LockMode.SHARED) {
                // If this txn already has EXCLUSIVE or SHARED, return immediately
                if (txnId.equals(exclusiveHolder) || sharedHolders.contains(txnId)) {
                    return;
                }

                // Wait until no other transaction holds EXCLUSIVE
                while (exclusiveHolder != null && !exclusiveHolder.equals(txnId)) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        throw new DeadlockException(
                                "Lock timeout acquiring SHARED lock on " + resource + " by " + txnId);
                    }
                    try {
                        wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new DeadlockException(
                                "Interrupted while acquiring SHARED lock on " + resource + " by " + txnId, e);
                    }
                }

                sharedHolders.add(txnId);

            } else { // EXCLUSIVE
                // If this txn already holds EXCLUSIVE, return immediately
                if (txnId.equals(exclusiveHolder)) {
                    return;
                }

                // Check for lock upgrade: txnId already holds SHARED
                if (sharedHolders.contains(txnId)) {
                    // Wait until this txn is the ONLY shared holder and no exclusive holder
                    while (exclusiveHolder != null || sharedHolders.size() > 1) {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0) {
                            throw new DeadlockException(
                                    "Lock timeout upgrading to EXCLUSIVE lock on " + resource + " by " + txnId);
                        }
                        try {
                            wait(remaining);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new DeadlockException(
                                    "Interrupted while upgrading to EXCLUSIVE lock on " + resource + " by " + txnId, e);
                        }
                    }

                    sharedHolders.remove(txnId);
                    exclusiveHolder = txnId;
                    return;
                }

                // Fresh exclusive request: wait until no shared holders and no exclusive holder
                while (exclusiveHolder != null || !sharedHolders.isEmpty()) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        throw new DeadlockException(
                                "Lock timeout acquiring EXCLUSIVE lock on " + resource + " by " + txnId);
                    }
                    try {
                        wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new DeadlockException(
                                "Interrupted while acquiring EXCLUSIVE lock on " + resource + " by " + txnId, e);
                    }
                }

                exclusiveHolder = txnId;
            }
        }

        synchronized boolean release(TransactionId txnId) {
            boolean released = false;
            if (txnId.equals(exclusiveHolder)) {
                exclusiveHolder = null;
                released = true;
            }
            if (sharedHolders.remove(txnId)) {
                released = true;
            }

            if (released) {
                notifyAll();
            }

            return exclusiveHolder == null && sharedHolders.isEmpty();
        }

        synchronized boolean isLocked() {
            return exclusiveHolder != null || !sharedHolders.isEmpty();
        }

        synchronized Optional<LockMode> getLockMode(TransactionId txnId) {
            if (txnId.equals(exclusiveHolder)) {
                return Optional.of(LockMode.EXCLUSIVE);
            }
            if (sharedHolders.contains(txnId)) {
                return Optional.of(LockMode.SHARED);
            }
            return Optional.empty();
        }
    }
}
