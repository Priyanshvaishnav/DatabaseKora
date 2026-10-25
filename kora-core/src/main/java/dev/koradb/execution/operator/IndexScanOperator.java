package dev.koradb.execution.operator;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.catalog.Table;
import dev.koradb.index.Index;
import dev.koradb.index.IndexKey;
import dev.koradb.storage.RecordId;
import dev.koradb.storage.TableFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Index scan operator.
 *
 * <p>Uses a B+ Tree index to execute point lookups or range scans,
 * streaming matching records by their {@link RecordId}.
 */
public class IndexScanOperator implements Operator {

    private final TableFile tableFile;
    private final Table table;
    private final Index index;
    private final IndexKey lowKey;
    private final IndexKey highKey;
    private final boolean lowInclusive;
    private final boolean highInclusive;
    private final Schema schema;
    private final String tableName;

    private List<RecordId> rids;
    private int cursor = 0;

    public IndexScanOperator(TableFile tableFile, Table table, Index index,
                             IndexKey lowKey, IndexKey highKey,
                             boolean lowInclusive, boolean highInclusive) {
        this.tableFile = tableFile;
        this.table = table;
        this.index = index;
        this.lowKey = lowKey;
        this.highKey = highKey;
        this.lowInclusive = lowInclusive;
        this.highInclusive = highInclusive;
        this.schema = table.getSchema();
        this.tableName = table.getName();
    }

    /** Helper for exact point lookups (WHERE col = val). */
    public static IndexScanOperator point(TableFile tableFile, Table table, Index index, IndexKey key) {
        return new IndexScanOperator(tableFile, table, index, key, key, true, true);
    }

    @Override
    public void open() {
        if (lowKey != null && lowKey.equals(highKey) && lowInclusive && highInclusive) {
            rids = index.isUnique()
                    ? index.search(lowKey).map(List::of).orElse(List.of())
                    : index.searchAll(lowKey);
        } else {
            rids = index.rangeScan(lowKey, highKey, lowInclusive, highInclusive);
        }
        cursor = 0;
        seen = new java.util.HashSet<>();
    }

    private java.util.Set<RecordId> seen = new java.util.HashSet<>();

    /** Re-checks the row's current key against the scan bounds (the heap is the source of truth). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean keyInRange(Object value) {
        if (value == null) return false;
        dev.koradb.index.IndexKey k = new dev.koradb.index.IndexKey(value, index.getKeyType());
        if (lowKey != null) {
            int c = k.compareTo(lowKey);
            if (c < 0 || (c == 0 && !lowInclusive)) return false;
        }
        if (highKey != null) {
            int c = k.compareTo(highKey);
            if (c > 0 || (c == 0 && !highInclusive)) return false;
        }
        return true;
    }

    @Override
    public Row next() {
        if (rids == null) return null;
        dev.koradb.mvcc.ReadView rv = dev.koradb.mvcc.MvccContext.getReadView();
        while (cursor < rids.size()) {
            RecordId rid = rids.get(cursor++);
            if (!seen.add(rid)) continue;                 // an index may hold the same RID twice
            Row raw = tableFile != null ? tableFile.readRow(rid, rv) : null;
            if (raw != null && keyInRange(raw.get(index.getColumnName()))) {   // index entries are hints
                Map<String, Object> map = new LinkedHashMap<>(raw.values());
                for (Map.Entry<String, Object> entry : raw.values().entrySet()) {
                    map.put(tableName + "." + entry.getKey(), entry.getValue());
                }
                return new Row(map, raw.getXmin(), raw.getXmax());
            }
        }
        return null;
    }

    @Override
    public void close() {
        rids = null;
    }

    @Override
    public Schema getSchema() {
        return schema;
    }

    public Index getIndex() {
        return index;
    }
}
