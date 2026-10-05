package dev.koradb.transaction.undo;

import dev.koradb.catalog.Catalog;
import dev.koradb.catalog.DiskTable;
import dev.koradb.catalog.Row;
import dev.koradb.catalog.Table;
import dev.koradb.index.Index;
import dev.koradb.index.IndexKey;
import dev.koradb.storage.RecordId;

import java.util.Map;
import java.util.Objects;

/**
 * Reverses an UPDATE operation by restoring the previous row values or re-inserting the old row.
 */
public class UpdateUndoAction implements UndoAction {

    private final String tableName;
    private final Row oldRow;
    private final Row newRow;
    private final RecordId newRecordId;

    public UpdateUndoAction(String tableName, Row oldRow, Row newRow, RecordId newRecordId) {
        this.tableName = Objects.requireNonNull(tableName, "tableName must not be null");
        this.oldRow = Objects.requireNonNull(oldRow, "oldRow must not be null");
        this.newRow = Objects.requireNonNull(newRow, "newRow must not be null");
        this.newRecordId = newRecordId;
    }

    public UpdateUndoAction(String tableName, Row oldRow, Row newRow) {
        this(tableName, oldRow, newRow, null);
    }

    @Override
    public void undo(Catalog catalog) {
        Table table = catalog.getTable(tableName);
        oldRow.setXmax(0L);
        if (table instanceof DiskTable dt && newRecordId != null) {
            // Delete the updated version from disk and index
            dt.getTableFile().deleteRow(newRecordId);
            for (Index idx : catalog.getIndexesForTable(tableName)) {
                Object newVal = newRow.get(idx.getColumnName());
                if (newVal != null) {
                    idx.delete(new IndexKey(newVal, idx.getKeyType()), newRecordId);
                }
            }
        } else {
            // In-memory update: restore original field values directly and remove newRow if separate
            table.mutableRows().remove(newRow);
            if (!table.mutableRows().contains(oldRow)) {
                table.mutableRows().add(oldRow);
            }
            for (Map.Entry<String, Object> entry : oldRow.values().entrySet()) {
                newRow.set(entry.getKey(), entry.getValue());
            }
        }
    }

    public String getTableName() {
        return tableName;
    }

    public Row getOldRow() {
        return oldRow;
    }

    public Row getNewRow() {
        return newRow;
    }

    public RecordId getNewRecordId() {
        return newRecordId;
    }
}
