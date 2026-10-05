package dev.koradb.execution.operator;

import dev.koradb.catalog.DiskTable;
import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.catalog.Table;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sequential table scan operator.
 *
 * <p>Pulls tuples one by one from the underlying {@link Table} (disk-backed or in-memory).
 * Tuples are augmented with table-prefixed keys (e.g. {@code users.id} alongside {@code id})
 * so downstream operators like Joins and Projections can resolve qualified and unqualified references.
 */
public class SeqScanOperator implements Operator {

    private final Table table;
    private final String tableName;
    private final Schema schema;
    private final dev.koradb.mvcc.ReadView readView;
    private Iterator<Row> iterator;

    public SeqScanOperator(Table table) {
        this(table, table.getName(), null);
    }

    public SeqScanOperator(Table table, String tableName) {
        this(table, tableName, null);
    }

    public SeqScanOperator(Table table, String tableName, dev.koradb.mvcc.ReadView readView) {
        this.table = table;
        this.tableName = tableName;
        this.schema = table.getSchema();
        this.readView = readView;
    }

    @Override
    public void open() {
        dev.koradb.mvcc.ReadView rv = this.readView != null ? this.readView : dev.koradb.mvcc.MvccContext.getReadView();
        if (table instanceof DiskTable diskTable) {
            this.iterator = diskTable.getRows(rv).iterator();
        } else {
            this.iterator = table.getRows(rv).iterator();
        }
    }

    @Override
    public Row next() {
        if (iterator != null && iterator.hasNext()) {
            Row raw = iterator.next();
            Map<String, Object> map = new LinkedHashMap<>(raw.values());
            for (Map.Entry<String, Object> entry : raw.values().entrySet()) {
                map.put(tableName + "." + entry.getKey(), entry.getValue());
            }
            return new Row(map);
        }
        return null;
    }

    @Override
    public void close() {
        iterator = null;
    }

    @Override
    public Schema getSchema() {
        return schema;
    }

    public String getTableName() {
        return tableName;
    }
}
