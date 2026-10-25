package dev.koradb.transaction;

import dev.koradb.transaction.undo.UndoAction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Context and state for an individual database transaction.
 *
 * <p>Maintains the transaction lifecycle, isolation level, and an in-memory
 * LIFO undo stack used to reverse modifications during abort/rollback.
 */
public class Transaction {

    private final TransactionId id;
    private volatile IsolationLevel isolationLevel;
    private final long startTimeMillis;
    private final Deque<UndoAction> undoStack = new ArrayDeque<>();
    private long prevLsn = 0L;

    private volatile TransactionState state;

    public Transaction(TransactionId id, IsolationLevel isolationLevel) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.isolationLevel = Objects.requireNonNull(isolationLevel, "isolationLevel must not be null");
        this.startTimeMillis = System.currentTimeMillis();
        this.state = TransactionState.ACTIVE;
    }

    public Transaction(TransactionId id) {
        this(id, IsolationLevel.SERIALIZABLE);
    }

    public TransactionId getId() {
        return id;
    }

    public IsolationLevel getIsolationLevel() {
        return isolationLevel;
    }

    public void setIsolationLevel(IsolationLevel isolationLevel) {
        this.isolationLevel = Objects.requireNonNull(isolationLevel, "isolationLevel must not be null");
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public TransactionState getState() {
        return state;
    }

    public boolean isActive() {
        return state == TransactionState.ACTIVE;
    }

    public synchronized long getPrevLsn() {
        return prevLsn;
    }

    public synchronized void setPrevLsn(long prevLsn) {
        this.prevLsn = prevLsn;
    }

    /**
     * Appends an undo action to the LIFO undo stack.
     */
    public synchronized void addUndoAction(UndoAction action) {
        Objects.requireNonNull(action, "action must not be null");
        if (state != TransactionState.ACTIVE) {
            throw new IllegalStateException("Cannot add undo action to inactive transaction " + id);
        }
        undoStack.push(action);
    }

    /**
     * Pops the most recent undo action from the stack.
     */
    public synchronized UndoAction popUndoAction() {
        return undoStack.poll();
    }

    /**
     * Checks if there are any remaining undo actions to apply.
     */
    public synchronized boolean hasUndoActions() {
        return !undoStack.isEmpty();
    }

    /**
     * Returns the number of registered undo actions in this transaction.
     */
    public synchronized int getUndoActionCount() {
        return undoStack.size();
    }

    /**
     * Marks the transaction as committed.
     */
    public synchronized void markCommitted() {
        if (state != TransactionState.ACTIVE) {
            throw new IllegalStateException("Cannot commit transaction in state: " + state);
        }
        state = TransactionState.COMMITTED;
        undoStack.clear();
    }

    /**
     * Marks the transaction as aborted.
     */
    public synchronized void markAborted() {
        if (state == TransactionState.COMMITTED) {
            throw new IllegalStateException("Cannot abort already committed transaction: " + id);
        }
        state = TransactionState.ABORTED;
    }

    private volatile dev.koradb.mvcc.ReadView readView;

    public dev.koradb.mvcc.ReadView getReadView() {
        return readView;
    }

    public void setReadView(dev.koradb.mvcc.ReadView readView) {
        this.readView = readView;
    }

    public synchronized dev.koradb.mvcc.ReadView getOrCreateReadView(java.util.function.Supplier<dev.koradb.mvcc.ReadView> factory) {
        if (readView == null) {
            readView = factory.get();
        }
        return readView;
    }

    public void clearReadView() {
        this.readView = null;
    }

    @Override
    public String toString() {
        return "Transaction{" +
                "id=" + id +
                ", state=" + state +
                ", isolation=" + isolationLevel +
                ", undoActions=" + undoStack.size() +
                '}';
    }

    // =========================================================================
    // WAL binding (a transaction writes to exactly one database's WAL)
    // =========================================================================

    private volatile java.util.function.LongPredicate abortedCheck = id -> false;
    private volatile dev.koradb.storage.wal.LogManager boundLogManager;
    private final java.util.Map<String, dev.koradb.storage.TableFile> touchedTables = new java.util.concurrent.ConcurrentHashMap<>();

    /** Installed by the TransactionManager so storage code can tell whether another txn aborted. */
    public void setAbortedCheck(java.util.function.LongPredicate check) {
        this.abortedCheck = java.util.Objects.requireNonNull(check);
    }

    public boolean isOtherTxnAborted(long txnId) {
        return abortedCheck.test(txnId);
    }

    /**
     * Binds this transaction to the WAL of the database it is modifying, writing its BEGIN record
     * there on the first write. A transaction may modify only one database: its undo chain
     * (prevLsn links) must live in a single WAL file.
     */
    public synchronized void bindWal(dev.koradb.storage.wal.LogManager lm, dev.koradb.storage.TableFile table) {
        if (lm == null) return;
        if (boundLogManager == null) {
            boundLogManager = lm;
            if (prevLsn == 0L) {
                prevLsn = lm.appendBegin(id.value(), 0L);
            }
        } else if (boundLogManager != lm) {
            throw new dev.koradb.exception.ExecutionException(
                    "A transaction cannot modify more than one database; COMMIT or ROLLBACK first");
        }
        touchedTables.putIfAbsent(table.getTableName().toLowerCase(), table);
    }

    /** Pre-binds the WAL without writing a data record (used when BEGIN was logged eagerly). */
    public synchronized void presetWal(dev.koradb.storage.wal.LogManager lm) {
        if (boundLogManager == null) boundLogManager = lm;
    }

    public dev.koradb.storage.wal.LogManager getBoundLogManager() {
        return boundLogManager;
    }

    public dev.koradb.storage.TableFile getTouchedTable(String tableName) {
        return tableName == null ? null : touchedTables.get(tableName.toLowerCase());
    }

    public java.util.Collection<dev.koradb.storage.TableFile> getTouchedTables() {
        return touchedTables.values();
    }

    /** Discards in-memory undo actions (used when the rollback is driven by the WAL instead). */
    public void clearUndoActions() {
        undoStack.clear();
    }

}
