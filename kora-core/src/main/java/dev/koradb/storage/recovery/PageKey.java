package dev.koradb.storage.recovery;

import java.util.Objects;

/**
 * Composite identifier representing a specific page within a table.
 */
public record PageKey(String tableName, int pageId) {
    public PageKey {
        Objects.requireNonNull(tableName, "tableName must not be null");
        tableName = tableName.toLowerCase();
    }

    @Override
    public String toString() {
        return tableName + ":" + pageId;
    }
}
