package dev.koradb.execution.operator;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.sql.ast.ColumnDef;

import java.util.*;

/**
 * Hash join operator for equijoins.
 *
 * <p>Builds an in-memory hash table on the inner relation during {@link #open()}
 * and probes it with tuples from the outer relation in $O(N + M)$ time.
 */
public class HashJoinOperator implements Operator {

    private final Operator outer;
    private final Operator inner;
    private final String outerCol;
    private final String innerCol;
    private final Schema schema;

    private Map<Object, List<Row>> hashTable;
    private Row currentOuter;
    private Iterator<Row> currentMatches;

    public HashJoinOperator(Operator outer, Operator inner, String outerCol, String innerCol) {
        this.outer = outer;
        this.inner = inner;
        this.outerCol = outerCol;
        this.innerCol = innerCol;
        this.schema = mergeSchemas(outer.getSchema(), inner.getSchema());
    }

    private static Schema mergeSchemas(Schema s1, Schema s2) {
        List<ColumnDef> cols = new ArrayList<>(s1.columns());
        for (ColumnDef c2 : s2.columns()) {
            if (!s1.hasColumn(c2.name())) {
                cols.add(c2);
            } else {
                cols.add(new ColumnDef(c2.name() + "_2", c2.dataType()));
            }
        }
        return new Schema(cols);
    }

    @Override
    public void open() {
        inner.open();
        hashTable = new HashMap<>();
        Row innerRow;
        while ((innerRow = inner.next()) != null) {
            Object key = getColValue(innerRow, innerCol);
            if (key != null) {
                hashTable.computeIfAbsent(key, k -> new ArrayList<>()).add(innerRow);
            }
        }
        inner.close();

        outer.open();
        currentMatches = Collections.emptyIterator();
    }

    private Object getColValue(Row row, String colName) {
        Object val = row.get(colName);
        if (val != null) return val;
        if (colName.contains(".")) {
            String shortName = colName.substring(colName.indexOf('.') + 1);
            val = row.get(shortName);
            if (val != null) return val;
        } else {
            for (Map.Entry<String, Object> entry : row.values().entrySet()) {
                if (entry.getKey().endsWith("." + colName)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    @Override
    public Row next() {
        while (true) {
            if (currentMatches != null && currentMatches.hasNext()) {
                Row innerMatch = currentMatches.next();
                return mergeRows(currentOuter, innerMatch);
            }

            currentOuter = outer.next();
            if (currentOuter == null) {
                return null;
            }

            Object key = getColValue(currentOuter, outerCol);
            if (key != null && hashTable.containsKey(key)) {
                currentMatches = hashTable.get(key).iterator();
            } else {
                currentMatches = Collections.emptyIterator();
            }
        }
    }

    private Row mergeRows(Row r1, Row r2) {
        Map<String, Object> map = new LinkedHashMap<>(r1.values());
        for (Map.Entry<String, Object> entry : r2.values().entrySet()) {
            if (!map.containsKey(entry.getKey())) {
                map.put(entry.getKey(), entry.getValue());
            } else {
                map.put(entry.getKey() + "_2", entry.getValue());
            }
        }
        for (Map.Entry<String, Object> entry : r2.values().entrySet()) {
            if (entry.getKey().contains(".")) {
                map.put(entry.getKey(), entry.getValue());
            }
        }
        return new Row(map);
    }

    @Override
    public void close() {
        hashTable = null;
        currentOuter = null;
        currentMatches = null;
        outer.close();
    }

    @Override
    public Schema getSchema() {
        return schema;
    }
}
