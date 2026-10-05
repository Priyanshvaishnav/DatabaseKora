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
    private final IsolationLevel isolationLevel;
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

    @Override
    public String toString() {
        return "Transaction{" +
                "id=" + id +
                ", state=" + state +
                ", isolation=" + isolationLevel +
                ", undoActions=" + undoStack.size() +
                '}';
    }
}
