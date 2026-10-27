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
    private final String groupByColumn;
    private final Schema schema;

    private List<Row> aggregatedRows;
    private int cursor = 0;

    public AggregateOperator(Operator child, List<SelectItem> selectItems, String groupByColumn) {
        this.child = child;
        this.selectItems = List.copyOf(selectItems);
        this.groupByColumn = groupByColumn;
        this.schema = computeSchema();
    }

    private Schema computeSchema() {
        List<ColumnDef> cols = new ArrayList<>();
        if (groupByColumn != null) {
            ColumnDef groupCol = child.getSchema().findColumn(groupByColumn);
            DataType type = groupCol != null ? groupCol.dataType() : DataType.VARCHAR;
            cols.add(new ColumnDef(groupByColumn, type));
        }
        for (SelectItem item : selectItems) {
            if (item.isAggregate()) {
                DataType type = switch (item.aggregateType()) {
                    case COUNT -> DataType.BIGINT;
                    case AVG -> DataType.DOUBLE;
                    case SUM -> {
                        // SUM over an integer column stays an integer (m4); over DOUBLE it is DOUBLE
                        ColumnDef c = child.getSchema().findColumn(item.aggregateColumn());
                        yield c != null && (c.dataType() == DataType.INT || c.dataType() == DataType.BIGINT)
                                ? DataType.BIGINT : DataType.DOUBLE;
                    }
                    case MIN, MAX -> {
                        ColumnDef c = child.getSchema().findColumn(item.aggregateColumn());
                        yield c != null ? c.dataType() : DataType.VARCHAR;
                    }
                };
                cols.add(new ColumnDef(item.alias(), type));
            } else if (groupByColumn != null && item.expression().equalsIgnoreCase(groupByColumn)) {
                // Already added
            } else {
                ColumnDef c = child.getSchema().findColumn(item.expression());
                DataType type = c != null ? c.dataType() : DataType.VARCHAR;
                cols.add(new ColumnDef(item.alias(), type));
            }
        }
        return new Schema(cols);
    }

    @Override
    public void open() {
        child.open();
        aggregatedRows = new ArrayList<>();

        if (groupByColumn != null) {
            // Grouped aggregation
            Map<Object, List<Row>> groups = new LinkedHashMap<>();
            Row r;
            while ((r = child.next()) != null) {
                Object key = getVal(r, groupByColumn);
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }

            for (Map.Entry<Object, List<Row>> entry : groups.entrySet()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put(groupByColumn, entry.getKey());
                calculateAggregates(entry.getValue(), map);
                aggregatedRows.add(new Row(map));
            }
        } else {
            // Scalar aggregation
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void calculateAggregates(List<Row> rows, Map<String, Object> output) {
        for (SelectItem item : selectItems) {
            if (!item.isAggregate()) {
                if (groupByColumn != null && item.expression().equalsIgnoreCase(groupByColumn)) {
                    output.put(item.alias(), output.get(groupByColumn));
                }
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
