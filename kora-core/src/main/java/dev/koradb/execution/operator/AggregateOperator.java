package dev.koradb.execution.operator;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.sql.ast.ColumnDef;
import dev.koradb.sql.ast.AggregateType;
import dev.koradb.sql.ast.DataType;
import dev.koradb.sql.ast.SelectItem;

import java.util.*;

/**
 * Aggregate operator.
 *
 * <p>Computes scalar aggregations across all rows or grouped aggregations
 * with {@code GROUP BY} (COUNT, SUM, AVG, MIN, MAX).
 */
public class AggregateOperator implements Operator {

    private final Operator child;
    private final List<SelectItem> selectItems;
    private final List<String> groupByColumns;
    private final Schema schema;

    private List<Row> aggregatedRows;
    private int cursor = 0;

    public AggregateOperator(Operator child, List<SelectItem> selectItems, String groupByColumn) {
        this(child, selectItems, groupByColumn == null ? List.of() : List.of(groupByColumn));
    }

    /**
     * @param groupByColumns GROUP BY columns; empty for a scalar aggregate (one output row)
     */
    public AggregateOperator(Operator child, List<SelectItem> selectItems, List<String> groupByColumns) {
        this.child = child;
        this.selectItems = List.copyOf(selectItems);
        this.groupByColumns = List.copyOf(groupByColumns);
        this.schema = computeSchema();
    }

    /** Output columns: the select items in order (group columns first for the legacy SELECT-less case). */
    private Schema computeSchema() {
        List<ColumnDef> cols = new ArrayList<>();
        Schema in = child.getSchema();
        if (selectItems.isEmpty()) {
            for (String g : groupByColumns) cols.add(new ColumnDef(g, typeOf(in, g)));
            return new Schema(cols);
        }
        for (SelectItem item : selectItems) {
            if (item.isAggregate()) {
                DataType type = switch (item.aggregateType()) {
                    case COUNT -> DataType.BIGINT;
                    case AVG -> DataType.DOUBLE;
                    case SUM -> {
                        // SUM over an integer column stays an integer (m4); over DOUBLE it is DOUBLE
                        DataType t = typeOf(in, item.aggregateColumn());
                        yield t == DataType.INT || t == DataType.BIGINT ? DataType.BIGINT : DataType.DOUBLE;
                    }
                    case MIN, MAX -> typeOf(in, item.aggregateColumn());
                };
                cols.add(new ColumnDef(item.alias(), type));
            } else {
                cols.add(new ColumnDef(item.alias(), typeOf(in, item.expression())));
            }
        }
        return new Schema(cols);
    }

    private static DataType typeOf(Schema s, String col) {
        ColumnDef c = s.findColumn(col);
        if (c == null && col.contains(".")) c = s.findColumn(col.substring(col.indexOf('.') + 1));
        return c != null ? c.dataType() : DataType.VARCHAR;
    }

    @Override
    public void open() {
        child.open();
        aggregatedRows = new ArrayList<>();
        if (!groupByColumns.isEmpty()) {
            // Grouped aggregation; NULL keys form one group
            Map<List<Object>, List<Row>> groups = new LinkedHashMap<>();
            Map<List<Object>, List<Object>> groupValues = new HashMap<>();
            Row r;
            while ((r = child.next()) != null) {
                List<Object> values = new ArrayList<>(groupByColumns.size());
                List<Object> key = new ArrayList<>(groupByColumns.size());
                for (String g : groupByColumns) {
                    Object v = getVal(r, g);
                    values.add(v);
                    key.add(JoinSupport.hashKey(v));
                }
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
                groupValues.putIfAbsent(key, values);
            }
            for (Map.Entry<List<Object>, List<Row>> entry : groups.entrySet()) {
                List<Object> values = groupValues.get(entry.getKey());
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < groupByColumns.size(); i++) {
                    map.put(groupByColumns.get(i), values.get(i));
                }
                calculateAggregates(entry.getValue(), map);
                aggregatedRows.add(new Row(map));
            }
        } else {
            // Scalar aggregation: always one row (COUNT(*) = 0 over no rows)
            List<Row> allRows = new ArrayList<>();
            Row r;
            while ((r = child.next()) != null) {
                allRows.add(r);
            }
            Map<String, Object> map = new LinkedHashMap<>();
            calculateAggregates(allRows, map);
            aggregatedRows.add(new Row(map));
        }
        cursor = 0;
    }

    /** The GROUP BY column a plain select item refers to ("city", "customers.city"), or null. */
    private String groupColumnFor(String expr) {
        for (String g : groupByColumns) {
            if (g.equalsIgnoreCase(expr)) return g;
        }
        String shortExpr = expr.contains(".") ? expr.substring(expr.indexOf('.') + 1) : expr;
        for (String g : groupByColumns) {
            String shortG = g.contains(".") ? g.substring(g.indexOf('.') + 1) : g;
            if (shortG.equalsIgnoreCase(shortExpr) && (!expr.contains(".") || !g.contains("."))) return g;
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void calculateAggregates(List<Row> rows, Map<String, Object> output) {
        for (SelectItem item : selectItems) {
            if (!item.isAggregate()) {
                String g = groupColumnFor(item.expression());
                if (g == null) {
                    throw new dev.koradb.exception.ExecutionException("Column '" + item.expression()
                            + "' must appear in GROUP BY or be used in an aggregate function");
                }
                output.put(item.alias(), output.get(g));
                continue;
            }

            AggregateType type = item.aggregateType();
            String col = item.aggregateColumn();
            String alias = item.alias();

            switch (type) {
                case COUNT -> {
                    if ("*".equals(col)) {
                        output.put(alias, (long) rows.size());
                    } else {
                        long count = 0;
                        for (Row r : rows) {
                            if (getVal(r, col) != null) count++;
                        }
                        output.put(alias, count);
                    }
                }
                case SUM -> {
                    double sum = 0.0;
                    long longSum = 0L;
                    boolean integral = true;
                    boolean hasVal = false;
                    for (Row r : rows) {
                        Object val = getVal(r, col);
                        if (val instanceof Number n) {
                            sum += n.doubleValue();
                            if (n instanceof Integer || n instanceof Long) {
                                try {
                                    longSum = Math.addExact(longSum, n.longValue());
                                } catch (ArithmeticException overflow) {
                                    throw new dev.koradb.exception.ExecutionException("SUM(" + col + ") overflows BIGINT");
                                }
                            } else {
                                integral = false;
                            }
                            hasVal = true;
                        }
                    }
                    output.put(alias, !hasVal ? null : (integral ? (Object) longSum : (Object) sum));
                }
                case AVG -> {
                    double sum = 0.0;
                    int count = 0;
                    for (Row r : rows) {
                        Object val = getVal(r, col);
                        if (val instanceof Number n) {
                            sum += n.doubleValue();
                            count++;
                        }
                    }
                    output.put(alias, count > 0 ? (sum / count) : null);
                }
                case MIN -> {
                    Comparable min = null;
                    for (Row r : rows) {
                        Object val = getVal(r, col);
                        if (val instanceof Comparable c) {
                            if (min == null || c.compareTo(min) < 0) {
                                min = c;
                            }
                        }
                    }
                    output.put(alias, min);
                }
                case MAX -> {
                    Comparable max = null;
                    for (Row r : rows) {
                        Object val = getVal(r, col);
                        if (val instanceof Comparable c) {
                            if (max == null || c.compareTo(max) > 0) {
                                max = c;
                            }
                        }
                    }
                    output.put(alias, max);
                }
            }
        }
    }

    private Object getVal(Row row, String colName) {
        if ("*".equals(colName)) return null;
        return row.get(colName);
    }

    @Override
    public Row next() {
        if (aggregatedRows != null && cursor < aggregatedRows.size()) {
            return aggregatedRows.get(cursor++);
        }
        return null;
    }

    @Override
    public void close() {
        aggregatedRows = null;
        child.close();
    }

    @Override
    public Schema getSchema() {
        return schema;
    }
}
