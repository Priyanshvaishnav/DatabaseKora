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
    private final ConcurrentMap<TransactionId, Transaction> activeTransactions = new ConcurrentHashMap<>();
    private final LockManager lockManager;
    private final Catalog catalog;

    public TransactionManager(LockManager lockManager, Catalog catalog) {
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager must not be null");
        this.catalog = catalog;
    }

    public TransactionManager(LockManager lockManager) {
        this(lockManager, null);
    }

    public TransactionManager() {
        this(new LockManager(), null);
    }

    /**
     * Begins a new transaction with default SERIALIZABLE isolation level.
     */
    public Transaction beginTransaction() {
        return beginTransaction(IsolationLevel.SERIALIZABLE);
    }

    /**
     * Begins a new transaction with the specified isolation level.
     */
    public Transaction beginTransaction(IsolationLevel isolationLevel) {
        TransactionId id = new TransactionId(nextTxnId.getAndIncrement());
        Transaction txn = new Transaction(id, isolationLevel);
        activeTransactions.put(id, txn);
        log.debug("Started transaction {} with isolation {}", id, isolationLevel);
        return txn;
    }

    /**
     * Commits the active transaction and releases all held locks.
     */
    public void commitTransaction(Transaction txn) {
        Objects.requireNonNull(txn, "txn must not be null");
        if (!txn.isActive()) {
            throw new IllegalStateException("Cannot commit inactive transaction: " + txn.getId());
        }

        log.debug("Committing transaction {}", txn.getId());
        txn.markCommitted();

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
        } finally {
            lockManager.releaseAll(txn.getId());
            activeTransactions.remove(txn.getId());
        }
        log.info("Transaction {} aborted and rolled back", txn.getId());
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
