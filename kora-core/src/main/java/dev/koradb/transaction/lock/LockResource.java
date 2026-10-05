package dev.koradb.transaction.lock;

import java.util.Objects;

/**
 * Identifies a lockable database entity (e.g. an entire table or a specific record).
 *
 * @param tableName  name of the table
 * @param identifier record key or {@code "*"} for whole-table lock
 */
public record LockResource(String tableName, Object identifier) {

    public LockResource {
        Objects.requireNonNull(tableName, "tableName must not be null");
        Objects.requireNonNull(identifier, "identifier must not be null");
        tableName = tableName.toLowerCase();
    }

    public static LockResource table(String tableName) {
        return new LockResource(tableName, "*");
    }

    public static LockResource record(String tableName, Object recordKey) {
        return new LockResource(tableName, recordKey);
    }

    public boolean isTableLock() {
        return "*".equals(identifier);
    }

    @Override
    public String toString() {
        return tableName + ":" + identifier;
    }
}
