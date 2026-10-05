package dev.koradb.transaction;

import dev.koradb.catalog.Catalog;
import dev.koradb.transaction.lock.LockManager;
import dev.koradb.transaction.undo.UndoAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the full lifecycle of transactions: initiation, commit, rollback, and concurrency locks.
 */
public class TransactionManager {

    private static final Logger log = LoggerFactory.getLogger(TransactionManager.class);

    private final AtomicLong nextTxnId = new AtomicLong(1L);
    private volatile long initialTxnId = 1L;
    private final ConcurrentMap<TransactionId, Transaction> activeTransactions = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Boolean> committedTxnIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Boolean> abortedTxnIds = new ConcurrentHashMap<>();
    private final LockManager lockManager;
    private final Catalog catalog;
    private final dev.koradb.storage.wal.LogManager logManager;

    public TransactionManager(LockManager lockManager, Catalog catalog, dev.koradb.storage.wal.LogManager logManager) {
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager must not be null");
        this.catalog = catalog;
        this.logManager = logManager;
    }

    public TransactionManager(LockManager lockManager, Catalog catalog) {
        this(lockManager, catalog, null);
    }

    public TransactionManager(LockManager lockManager) {
        this(lockManager, null, null);
    }

    public TransactionManager() {
        this(new LockManager(), null, null);
    }

    /**
     * Begins a new transaction with default SERIALIZABLE isolation level.
     */
    public Transaction beginTransaction() {
        return beginTransaction(IsolationLevel.SERIALIZABLE);
    }

    private dev.koradb.storage.wal.LogManager activeLogManager() {
        if (catalog != null && catalog.getLogManager() != null) {
            return catalog.getLogManager();
        }
        return this.logManager;
    }

    /**
     * Begins a new transaction with the specified isolation level.
     */
    public Transaction beginTransaction(IsolationLevel isolationLevel) {
        TransactionId id = new TransactionId(nextTxnId.getAndIncrement());
        Transaction txn = new Transaction(id, isolationLevel);
        dev.koradb.storage.wal.LogManager lm = activeLogManager();
        if (lm != null) {
            long lsn = lm.appendBegin(id.value(), 0L);
            txn.setPrevLsn(lsn);
        }
        activeTransactions.put(id, txn);
        log.debug("Started transaction {} with isolation {}", id, isolationLevel);
        return txn;
    }

    /**
     * Commits the active transaction and releases all held locks.
     * Enforces the Force-Log-at-Commit rule by flushing the WAL up to the commit record.
     */
    public void commitTransaction(Transaction txn) {
        Objects.requireNonNull(txn, "txn must not be null");
        if (!txn.isActive()) {
            throw new IllegalStateException("Cannot commit inactive transaction: " + txn.getId());
        }

        log.debug("Committing transaction {}", txn.getId());
        dev.koradb.storage.wal.LogManager lm = activeLogManager();
        if (lm != null) {
            long commitLsn = lm.appendCommit(txn.getId().value(), txn.getPrevLsn());
            txn.setPrevLsn(commitLsn);
            lm.flush(commitLsn);
        }

        txn.markCommitted();
        committedTxnIds.put(txn.getId().value(), Boolean.TRUE);

        // Release all SS2PL locks held by this transaction
        lockManager.releaseAll(txn.getId());
        activeTransactions.remove(txn.getId());
        log.info("Transaction {} committed successfully", txn.getId());
    }

    /**
     * Aborts the transaction, reversing all changes via LIFO undo actions and releasing locks.
     */
    public void abortTransaction(Transaction txn) {
        abortTransaction(txn, this.catalog);
    }

    /**
     * Aborts the transaction against the specified catalog, reversing all changes and releasing locks.
     */
    public void abortTransaction(Transaction txn, Catalog targetCatalog) {
        Objects.requireNonNull(txn, "txn must not be null");
        if (txn.getState() == TransactionState.ABORTED) {
            return;
        }
        if (txn.getState() == TransactionState.COMMITTED) {
            throw new IllegalStateException("Cannot abort already committed transaction: " + txn.getId());
        }

        log.debug("Aborting transaction {} (applying undo actions)", txn.getId());
        txn.markAborted();
        abortedTxnIds.put(txn.getId().value(), Boolean.TRUE);

        try {
            while (txn.hasUndoActions()) {
                UndoAction action = txn.popUndoAction();
                if (action != null && targetCatalog != null) {
                    try {
                        action.undo(targetCatalog);
                    } catch (Exception e) {
                        log.error("Error executing undo action in transaction {}", txn.getId(), e);
                    }
                }
            }
            dev.koradb.storage.wal.LogManager lm = activeLogManager();
            if (lm != null) {
                long abortLsn = lm.appendAbort(txn.getId().value(), txn.getPrevLsn());
                txn.setPrevLsn(abortLsn);
                lm.flush(abortLsn);
            }
        } finally {
            lockManager.releaseAll(txn.getId());
            activeTransactions.remove(txn.getId());
        }
        log.info("Transaction {} aborted and rolled back", txn.getId());
    }

    public void recordCommitted(long txnId) {
        committedTxnIds.put(txnId, Boolean.TRUE);
    }

    public void recordAborted(long txnId) {
        abortedTxnIds.put(txnId, Boolean.TRUE);
    }

    public void initNextTxnId(long startId) {
        nextTxnId.updateAndGet(curr -> Math.max(curr, startId));
        this.initialTxnId = nextTxnId.get();
        log.info("Initialized nextTxnId to {} (initialTxnId={})", nextTxnId.get(), initialTxnId);
    }

    public long getInitialTxnId() {
        return initialTxnId;
    }

    public boolean isCommitted(long txnId) {
        if (txnId == 0L) return true;
        if (txnId < initialTxnId) {
            return !abortedTxnIds.containsKey(txnId);
        }
        return committedTxnIds.containsKey(txnId);
    }

    public boolean isAborted(long txnId) {
        return abortedTxnIds.containsKey(txnId);
    }

    public boolean isActive(long txnId) {
        return activeTransactions.containsKey(new TransactionId(txnId));
    }

    public long getNextTxnId() {
        return nextTxnId.get();
    }

    public dev.koradb.mvcc.ReadView createReadView(Transaction txn) {
        long creatorId = txn != null ? txn.getId().value() : 0L;
        java.util.Set<Long> activeIds = activeTransactions.keySet().stream()
                .map(TransactionId::value)
                .filter(id -> id != creatorId)
                .collect(java.util.stream.Collectors.toSet());
        long lowWatermark = activeIds.isEmpty() ? nextTxnId.get() : activeIds.stream().min(Long::compareTo).get();
        long highWatermark = nextTxnId.get();
        return new dev.koradb.mvcc.ReadView(creatorId, lowWatermark, highWatermark, activeIds, this::isCommitted, this::isAborted);
    }

    public dev.koradb.storage.wal.LogManager getLogManager() {
        return activeLogManager();
    }

    public LockManager getLockManager() {
        return lockManager;
    }

    public Catalog getCatalog() {
        return catalog;
    }

    public Optional<Transaction> getTransaction(TransactionId id) {
        return Optional.ofNullable(activeTransactions.get(id));
    }

    public Collection<Transaction> getActiveTransactions() {
        return Collections.unmodifiableCollection(activeTransactions.values());
    }

    public int activeTransactionCount() {
        return activeTransactions.size();
    }
}
