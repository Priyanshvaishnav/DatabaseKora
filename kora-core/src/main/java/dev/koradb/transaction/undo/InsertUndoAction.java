package dev.koradb.transaction.undo;

import dev.koradb.catalog.Catalog;
import dev.koradb.catalog.DiskTable;
import dev.koradb.catalog.Row;
import dev.koradb.catalog.Table;
import dev.koradb.index.Index;
import dev.koradb.index.IndexKey;
import dev.koradb.storage.RecordId;

import java.util.List;
import java.util.Objects;

/**
 * Reverses an INSERT operation by deleting the inserted row and updating indexes.
 */
public class InsertUndoAction implements UndoAction {

    private final String tableName;
    private final Row row;
    private final RecordId recordId;

    public InsertUndoAction(String tableName, Row row, RecordId recordId) {
        this.tableName = Objects.requireNonNull(tableName, "tableName must not be null");
        this.row = Objects.requireNonNull(row, "row must not be null");
        this.recordId = recordId;
    }

    public InsertUndoAction(String tableName, Row row) {
        this(tableName, row, null);
    }

    @Override
    public void undo(Catalog catalog) {
        Table table = catalog.getTable(tableName);
        if (table instanceof DiskTable dt && recordId != null) {
            dt.getTableFile().deleteRow(recordId);
            for (Index idx : catalog.getIndexesForTable(tableName)) {
                Object val = row.get(idx.getColumnName());
                if (val != null) {
                    idx.delete(new IndexKey(val, idx.getKeyType()), recordId);
                }
            }
        } else {
            List<Row> rows = table.mutableRows();
            for (int i = rows.size() - 1; i >= 0; i--) {
                if (rows.get(i) == row || rows.get(i).equals(row)) {
                    rows.remove(i);
                    break;
                }
            }
        }
    }

    public String getTableName() {
        return tableName;
    }

    public Row getRow() {
        return row;
    }

    public RecordId getRecordId() {
        return recordId;
    }
}
