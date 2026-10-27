package dev.koradb.execution.operator;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.sql.ast.OrderItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sort operator (ORDER BY).
 *
 * <p>Consumes all tuples from the child operator in {@link #open()},
 * sorts them according to the given sort criteria, and emits them one by one.
 */
public class SortOperator implements Operator {

    private final Operator child;
    private final List<OrderItem> orderItems;
    private List<Row> sortedRows;
    private int cursor;

    public SortOperator(Operator child, List<OrderItem> orderItems) {
        this.child = child;
        this.orderItems = orderItems != null ? List.copyOf(orderItems) : List.of();
    }

    @Override
    public void open() {
        child.open();
        List<Row> rows = new ArrayList<>();
        Row r;
        while ((r = child.next()) != null) {
            rows.add(r);
        }

        for (OrderItem item : orderItems) {
            if (!resolvable(item.columnName(), rows.isEmpty() ? null : rows.get(0))) {
                throw new dev.koradb.exception.ExecutionException("ORDER BY column '" + item.columnName() + "' does not exist");
            }
        }

        if (!orderItems.isEmpty()) {
            rows.sort((r1, r2) -> {
                for (OrderItem item : orderItems) {
                    Object v1 = getVal(r1, item.columnName());
                    Object v2 = getVal(r2, item.columnName());
                    int cmp = compareValues(v1, v2);
                    if (!item.ascending()) {
                        cmp = -cmp;
                    }
                    if (cmp != 0) return cmp;
                }
                return 0;
            });
        }

        this.sortedRows = rows;
        this.cursor = 0;
    }

    /** True when {@code colName} names a column of the input (by the same rules {@link #getVal} uses). */
    private boolean resolvable(String colName, Row sample) {
        if (sample != null) {
            for (String key : sample.values().keySet()) {
                if (matches(key, colName)) return true;
            }
            return false;
        }
        Schema schema = child.getSchema();
        if (schema == null || schema.columns().isEmpty()) return true;   // nothing to check against
        for (dev.koradb.sql.ast.ColumnDef c : schema.columns()) {
            if (matches(c.name(), colName)) return true;
        }
        return false;
    }

    private static boolean matches(String key, String colName) {
        if (key.equalsIgnoreCase(colName)) return true;
        String k = key.toLowerCase(), n = colName.toLowerCase();
        String shortName = n.contains(".") ? n.substring(n.indexOf('.') + 1) : n;
        return k.equals(shortName) || k.endsWith("." + shortName);
    }

    private Object getVal(Row row, String colName) {
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int compareValues(Object v1, Object v2) {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return 1;  // NULLs last
        if (v2 == null) return -1;
        if (v1 instanceof Number n1 && v2 instanceof Number n2) {
            return Double.compare(n1.doubleValue(), n2.doubleValue());
        }
        if (v1 instanceof Comparable c1 && v2 instanceof Comparable c2) {
            try {
                return c1.compareTo(c2);
            } catch (ClassCastException ignored) {}
        }
        return v1.toString().compareTo(v2.toString());
    }

    @Override
    public Row next() {
        if (sortedRows != null && cursor < sortedRows.size()) {
            return sortedRows.get(cursor++);
        }
        return null;
    }

    @Override
    public void close() {
        sortedRows = null;
        child.close();
    }

    @Override
    public Schema getSchema() {
        return child.getSchema();
    }
}
