package dev.koradb.session;

import dev.koradb.catalog.Catalog;
import dev.koradb.transaction.Transaction;

import java.util.Objects;
import java.util.UUID;

/**
 * Client connection session tracking active database context, explicit transaction state, and autocommit settings.
 */
public class Session {

    private final String sessionId;
    private volatile Catalog catalog;
    private volatile Transaction activeTransaction;
    private volatile boolean autoCommit = true;

    public Session(Catalog catalog) {
        this(UUID.randomUUID().toString(), catalog);
    }

    public Session(String sessionId, Catalog catalog) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
    }

    public String getSessionId() {
        return sessionId;
    }

    public Catalog getCatalog() {
        return catalog;
    }

    public void setCatalog(Catalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
    }

    public Transaction getActiveTransaction() {
        return activeTransaction;
    }

    public void setActiveTransaction(Transaction activeTransaction) {
        this.activeTransaction = activeTransaction;
    }

    public void clearActiveTransaction() {
        this.activeTransaction = null;
    }

    public boolean hasActiveTransaction() {
        return activeTransaction != null && activeTransaction.isActive();
    }

    public boolean isAutoCommit() {
        return autoCommit;
    }

    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    @Override
    public String toString() {
        return "Session{" +
                "id='" + sessionId + '\'' +
                ", hasTxn=" + hasActiveTransaction() +
                ", autoCommit=" + autoCommit +
                '}';
    }
}
