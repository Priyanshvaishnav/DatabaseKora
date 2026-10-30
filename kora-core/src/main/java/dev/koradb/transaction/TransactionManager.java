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
    private final Object snapshotLock = new Object();
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
    // ---- transaction-id reservation (ids are never reused across restarts) ----
    private volatile java.util.function.LongConsumer txnIdReserver = null;
    private volatile long reservedUpTo = Long.MAX_VALUE;
    private long reservationBlock = 1000L;

    /**
     * Installs a callback that durably records "ids below N may have been used". It is invoked
     * before an id at or beyond the current reservation is handed out.
     */
    public synchronized void setTxnIdReserver(java.util.function.LongConsumer reserver, long block) {
        this.txnIdReserver = reserver;
        this.reservationBlock = Math.max(1L, block);
        long limit = nextTxnId.get() + reservationBlock;
        reserver.accept(limit);
        this.reservedUpTo = limit;
    }

    private void ensureReserved(long id) {
        if (id < reservedUpTo) return;
        synchronized (this) {
            if (id >= reservedUpTo && txnIdReserver != null) {
                long limit = Math.max(id, nextTxnId.get()) + reservationBlock;
                txnIdReserver.accept(limit);
                reservedUpTo = limit;
            }
        }
    }

    public Transaction beginTransaction(IsolationLevel isolationLevel) {
        TransactionId id;
        Transaction txn;
        // Allocating the id and registering the txn as active must be atomic with respect to
        // createReadView(): a snapshot taken in between would see an id below its high watermark
        // that is neither active nor committed, and could treat its uncommitted writes as visible.
        synchronized (snapshotLock) {
            id = new TransactionId(following ? localReaderIds.getAndIncrement() : nextTxnId.getAndIncrement());
            txn = new Transaction(id, isolationLevel);
            activeTransactions.put(id, txn);
        }
        if (!following) {
            ensureReserved(id.value());
        }
        txn.setAbortedCheck(this::isAborted);
        // With a catalog, BEGIN is written lazily into the WAL of the database the transaction
        // first modifies (Transaction.bindWal). An explicitly supplied LogManager (no catalog)
        // keeps the old eager behaviour.
        if (catalog == null && this.logManager != null) {
            long lsn = this.logManager.appendBegin(id.value(), 0L);
            txn.setPrevLsn(lsn);
            txn.presetWal(this.logManager);
        }
        log.debug("Started transaction {} with isolation {}", id, isolationLevel);
        return txn;
    }

    /**
     * Commits the active transaction and releases all held locks.
     * Enforces the Force-Log-at-Commit rule by flushing the WAL up to the commit record.
     */
    /** Observes transaction outcomes (metrics). Only transactions that wrote data are reported as commits. */
    public interface OutcomeListener {
        void onCommit(Transaction txn);
        void onAbort(Transaction txn);
    }

    private final java.util.List<OutcomeListener> outcomeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addOutcomeListener(OutcomeListener l) {
        outcomeListeners.add(java.util.Objects.requireNonNull(l));
    }

    private void notifyOutcome(Transaction txn, boolean committed) {
        for (OutcomeListener l : outcomeListeners) {
            try {
                if (committed) l.onCommit(txn); else l.onAbort(txn);
            } catch (RuntimeException e) {
                log.debug("Transaction outcome listener failed: {}", e.getMessage());
            }
        }
    }

    @FunctionalInterface
    public interface CommitHook {
        void onCommit(long commitLsn);
    }

    private volatile CommitHook commitHook = null;

    public void setCommitHook(CommitHook commitHook) {
        this.commitHook = commitHook;
    }

    public CommitHook getCommitHook() {
        return commitHook;
    }

    public void commitTransaction(Transaction txn) {
        Objects.requireNonNull(txn, "txn must not be null");
        if (!txn.isActive()) {
            throw new IllegalStateException("Cannot commit inactive transaction: " + txn.getId());
        }

        log.debug("Committing transaction {}", txn.getId());
        long commitLsn = 0L;
        dev.koradb.storage.wal.LogManager lm = txn.getBoundLogManager();   // null = wrote nothing
        if (lm != null) {
            commitLsn = lm.appendCommit(txn.getId().value(), txn.getPrevLsn());
            txn.setPrevLsn(commitLsn);
            lm.flush(commitLsn);
        }

        // The commit hook (sync replication) tracks the WAL of the catalog's default database only;
        // LSNs of another database's WAL are a different sequence and must not be waited on.
        if (commitHook != null && commitLsn > 0L && (catalog == null || catalog.getLogManager() == null || lm == catalog.getLogManager())) {
            commitHook.onCommit(commitLsn);
        }

        txn.markCommitted();
        committedTxnIds.put(txn.getId().value(), Boolean.TRUE);

        // Leave the active set BEFORE releasing the SS2PL locks: a reader blocked on one of them
        // takes its ReadView as soon as it wakes up, and must not see this txn as still in flight
        // (it would then miss the rows this txn just committed).
        activeTransactions.remove(txn.getId());
        lockManager.releaseAll(txn.getId());
        notifyOutcome(txn, true);
        log.debug("Transaction {} committed successfully", txn.getId());
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

        log.debug("Aborting transaction {}", txn.getId());
        txn.markAborted();

        try {
            dev.koradb.storage.wal.LogManager lm = txn.getBoundLogManager();
            if (lm != null && !txn.getTouchedTables().isEmpty()) {
                // Disk tables: ARIES-style rollback from the WAL (before-images + CLRs + END).
                rollbackFromWal(txn, lm);
                txn.clearUndoActions();
            } else {
                // In-memory tables (or tables without a WAL): logical undo actions.
                while (txn.hasUndoActions()) {
                    UndoAction action = txn.popUndoAction();
                    if (action != null && targetCatalog != null) {
                        try {
                            action.undo(targetCatalog);
                        } catch (Exception e) {
                            log.error("Error executing undo action in transaction {}", txn.getId(), e);
                            throw new IllegalStateException("Rollback of transaction " + txn.getId() + " failed: " + e.getMessage(), e);
                        }
                    }
                }
                if (lm != null && txn.getPrevLsn() > 0) {
                    long abortLsn = lm.appendAbort(txn.getId().value(), txn.getPrevLsn());
                    txn.setPrevLsn(abortLsn);
                    lm.flush(abortLsn);
                }
            }
        } finally {
            // Publish the abort only AFTER every change is undone (finally runs after the rollback).
            // While rollback runs, this txn's xmax must keep blocking other writers; otherwise a writer
            // could stamp a row the rollback is about to restore, resurrecting a second live version.
            // As in commitTransaction(): leave the active set before the locks are released.
            abortedTxnIds.put(txn.getId().value(), Boolean.TRUE);
            activeTransactions.remove(txn.getId());
            lockManager.releaseAll(txn.getId());
        }
        notifyOutcome(txn, false);
        log.debug("Transaction {} aborted and rolled back", txn.getId());
    }

    public void recordCommitted(long txnId) {
        abortedTxnIds.remove(txnId);
        committedTxnIds.put(txnId, Boolean.TRUE);
    }

    public void recordAborted(long txnId) {
        committedTxnIds.remove(txnId);
        abortedTxnIds.put(txnId, Boolean.TRUE);
    }

    public void initNextTxnId(long startId) {
        nextTxnId.updateAndGet(curr -> Math.max(curr, startId));
        this.initialTxnId = nextTxnId.get();
        log.info("Initialized nextTxnId to {} (initialTxnId={})", nextTxnId.get(), initialTxnId);
    }

    public void updateNextTxnId(long txnId) {
        nextTxnId.updateAndGet(curr -> Math.max(curr, txnId));
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

    // ---- following a primary (replica) ----

    /** Ids of a replica's own (read-only) transactions: never the same as a transaction of the primary. */
    private final AtomicLong localReaderIds = new AtomicLong(1L << 62);
    private volatile boolean following = false;
    /** Transactions of the primary with changes applied here and no COMMIT or END yet. */
    private final java.util.Set<Long> remoteInFlight = ConcurrentHashMap.newKeySet();
    /** The order in which transactions of the primary committed here. */
    private final ConcurrentMap<Long, Long> remoteCommitOrder = new ConcurrentHashMap<>();
    private long remoteCommits = 0L;   // guarded by snapshotLock

    /** Puts this node in replica mode (see {@link #createReadView}), or takes it out when it is promoted. */
    public void setFollowing(boolean following) {
        this.following = following;
    }

    /** A transaction of the primary changed a page. Call before the change is applied. */
    public void remoteChange(long txnId) {
        remoteInFlight.add(txnId);
    }

    public void remoteCommit(long txnId) {
        synchronized (snapshotLock) {
            remoteCommitOrder.put(txnId, ++remoteCommits);   // before the removal: see isCommittedAsOf
        }
        recordCommitted(txnId);
        remoteInFlight.remove(txnId);
    }

    /** A transaction of the primary is fully rolled back: none of its changes are left on the pages. */
    public void remoteRolledBack(long txnId) {
        recordAborted(txnId);
        remoteInFlight.remove(txnId);
    }

    private boolean isCommittedAsOf(long txnId, long commitsAtSnapshot) {
        if (remoteInFlight.contains(txnId)) return false;
        Long order = remoteCommitOrder.get(txnId);
        // No record of it: its rows were here before this node started following (and it is not aborted).
        return order == null || order <= commitsAtSnapshot;
    }

    /**
     * On a replica, what is finished cannot be derived from transaction ids. They are assigned at
     * BEGIN on the primary, so a lower id can make its first change here after a higher id has
     * committed, and a watermark would take it for finished. So the low watermark is 0, which makes
     * every row's transaction go through this view's own commit check.
     */
    private dev.koradb.mvcc.ReadView createFollowerReadView() {
        long highWatermark;
        long commitsAtSnapshot;
        java.util.Set<Long> inFlight;
        synchronized (snapshotLock) {
            highWatermark = nextTxnId.get();
            commitsAtSnapshot = remoteCommits;
            inFlight = java.util.Set.copyOf(remoteInFlight);
        }
        return new dev.koradb.mvcc.ReadView(0L, 0L, highWatermark, inFlight,
                txnId -> isCommittedAsOf(txnId, commitsAtSnapshot), this::isAborted);
    }

    public dev.koradb.mvcc.ReadView createReadView(Transaction txn) {
        if (following) {
            return createFollowerReadView();
        }
        long creatorId = txn != null ? txn.getId().value() : 0L;
        java.util.Set<Long> activeIds;
        long highWatermark;
        synchronized (snapshotLock) {   // see beginTransaction(): no half-registered txn can be observed
            highWatermark = nextTxnId.get();
            activeIds = activeTransactions.keySet().stream()
                    .map(TransactionId::value)
                    .filter(id -> id != creatorId)
                    .collect(java.util.stream.Collectors.toSet());
        }
        long lowWatermark = activeIds.isEmpty() ? highWatermark : activeIds.stream().min(Long::compareTo).get();
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

    /**
     * Undoes every change of {@code txn} by walking its prevLsn chain backwards through its WAL,
     * restoring before-images and writing compensation records, then ABORT/END so recovery never
     * undoes it a second time.
     */
    private void rollbackFromWal(Transaction txn, dev.koradb.storage.wal.LogManager lm) {
        long txnId = txn.getId().value();
        long abortLsn = lm.appendAbort(txnId, txn.getPrevLsn());
        long chainLsn = txn.getPrevLsn();      // last data record before the ABORT
        txn.setPrevLsn(abortLsn);
        lm.flush(abortLsn);                    // records must be on disk to be read back
        try (dev.koradb.storage.wal.WalReader reader = new dev.koradb.storage.wal.WalReader(lm.getWalPath())) {
            long lsn = chainLsn;
            while (lsn > 0) {
                dev.koradb.storage.wal.LogRecord rec = reader.readAt(lsn);
                if (rec.getTxnId() != txnId) {
                    throw new IllegalStateException("WAL chain of txn " + txnId + " reached a record of txn " + rec.getTxnId() + " at LSN " + lsn);
                }
                if (rec instanceof dev.koradb.storage.wal.UpdateLogRecord u) {
                    dev.koradb.storage.TableFile tf = txn.getTouchedTable(u.getTableName());
                    if (tf == null) {
                        throw new IllegalStateException("Table '" + u.getTableName() + "' of txn " + txnId + " is not open");
                    }
                    long clr = tf.undoLogged(u, txnId, txn.getPrevLsn());
                    txn.setPrevLsn(clr);
                    lsn = u.getPrevLsn();
                } else if (rec instanceof dev.koradb.storage.wal.ClrLogRecord c) {
                    lsn = c.getUndoNextLsn();
                } else {
                    lsn = rec instanceof dev.koradb.storage.wal.BeginLogRecord ? 0 : rec.getPrevLsn();
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read WAL to roll back txn " + txnId, e);
        }
        long endLsn = lm.appendEnd(txnId, txn.getPrevLsn());
        txn.setPrevLsn(endLsn);
        lm.flush(endLsn);
    }

}
