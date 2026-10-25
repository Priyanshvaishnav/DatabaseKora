package dev.koradb.transaction.undo;

import dev.koradb.catalog.Catalog;
import dev.koradb.catalog.Row;
import dev.koradb.catalog.Table;

import java.util.Objects;

/**
 * Reverses a DELETE operation by re-inserting the deleted row into the table.
 */
public class DeleteUndoAction implements UndoAction {

    private final String tableName;
    private final Row deletedRow;

    private final dev.koradb.storage.RecordId recordId;
    private final long txnId;

    public DeleteUndoAction(String tableName, Row deletedRow) {
        this(tableName, deletedRow, null, 0L);
    }

    /** Disk-table variant: undo clears the xmax stamped on the ORIGINAL tuple instead of re-inserting a copy. */
    public DeleteUndoAction(String tableName, Row deletedRow, dev.koradb.storage.RecordId recordId, long txnId) {
        this.tableName = Objects.requireNonNull(tableName, "tableName must not be null");
        this.deletedRow = Objects.requireNonNull(deletedRow, "deletedRow must not be null");
        this.recordId = recordId;
        this.txnId = txnId;
    }

    @Override
    public void undo(Catalog catalog) {
        Table table = catalog.getTable(tableName);
        if (table instanceof dev.koradb.catalog.DiskTable dt && recordId != null) {
            dt.getTableFile().clearXmax(recordId, txnId);
            return;
        }
        deletedRow.setXmax(0L);
        if (!table.mutableRows().contains(deletedRow)) {
            table.insertRow(deletedRow);
        }
    }

    public String getTableName() {
        return tableName;
    }

    public Row getDeletedRow() {
        return deletedRow;
    }
}
