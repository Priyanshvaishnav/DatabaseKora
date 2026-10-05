package dev.koradb.execution.operator;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.sql.ast.ColumnDef;
import dev.koradb.execution.ExpressionEvaluator;
import dev.koradb.sql.ast.BinaryExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nested loop join operator.
 *
 * <p>For each tuple from the outer (left) operator, scans all tuples from the
 * inner (right) operator, evaluating the join predicate on each combined tuple.
 */
public class NestedLoopJoinOperator implements Operator {

    private final Operator outer;
    private final Operator inner;
    private final BinaryExpr condition;
    private final Schema schema;
    private final ExpressionEvaluator evaluator;

    private Row currentOuter;

    public NestedLoopJoinOperator(Operator outer, Operator inner, BinaryExpr condition) {
        this.outer = outer;
        this.inner = inner;
        this.condition = condition;
        this.schema = mergeSchemas(outer.getSchema(), inner.getSchema());
        this.evaluator = new ExpressionEvaluator(this.schema);
    }

    private static Schema mergeSchemas(Schema s1, Schema s2) {
        List<ColumnDef> cols = new ArrayList<>(s1.columns());
        for (ColumnDef c2 : s2.columns()) {
            if (!s1.hasColumn(c2.name())) {
                cols.add(c2);
            } else {
                // If collision, also register qualified/unique name
                cols.add(new ColumnDef(c2.name() + "_2", c2.dataType()));
            }
        }
        return new Schema(cols);
    }

    @Override
    public void open() {
        outer.open();
        inner.open();
        currentOuter = outer.next();
    }

    @Override
    public Row next() {
        while (currentOuter != null) {
            Row innerRow = inner.next();
            if (innerRow == null) {
                inner.close();
                inner.open();
                currentOuter = outer.next();
                continue;
            }

            Row joined = mergeRows(currentOuter, innerRow);
            if (condition == null || evaluator.evaluate(condition, joined)) {
                return joined;
            }
        }
        return null;
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
        // Also preserve qualified keys from r2
        for (Map.Entry<String, Object> entry : r2.values().entrySet()) {
            if (entry.getKey().contains(".")) {
                map.put(entry.getKey(), entry.getValue());
            }
        }
        return new Row(map);
    }

    @Override
    public void close() {
        currentOuter = null;
        outer.close();
        inner.close();
    }

    @Override
    public Schema getSchema() {
        return schema;
    }
}
