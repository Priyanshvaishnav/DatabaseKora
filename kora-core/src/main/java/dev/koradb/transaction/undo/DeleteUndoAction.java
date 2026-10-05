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

    public DeleteUndoAction(String tableName, Row deletedRow) {
        this.tableName = Objects.requireNonNull(tableName, "tableName must not be null");
        this.deletedRow = Objects.requireNonNull(deletedRow, "deletedRow must not be null");
    }

    @Override
    public void undo(Catalog catalog) {
        Table table = catalog.getTable(tableName);
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
