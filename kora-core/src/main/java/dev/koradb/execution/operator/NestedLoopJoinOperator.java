package dev.koradb.execution.operator;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.execution.ExpressionEvaluator;
import dev.koradb.sql.ast.Expression;

import java.util.ArrayList;
import java.util.List;

/**
 * Nested-loop join with an arbitrary ON condition. The inner side is read once and kept in memory.
 * With {@code leftOuter}, an outer row without any match is returned once with NULLs for the inner side.
 */
public class NestedLoopJoinOperator implements Operator {

    private final Operator outer;
    private final Operator inner;
    private final Expression condition;
    private final boolean leftOuter;
    private final List<String> innerQualifiers;
    private final Schema schema;
    private final ExpressionEvaluator evaluator;

    private List<Row> innerRows;
    private Row currentOuter;
    private int innerPos;
    private boolean matched;

    public NestedLoopJoinOperator(Operator outer, Operator inner, Expression condition) {
        this(outer, inner, condition, false, List.of());
    }

    public NestedLoopJoinOperator(Operator outer, Operator inner, Expression condition,
                                  boolean leftOuter, List<String> innerQualifiers) {
        this.outer = outer;
        this.inner = inner;
        this.condition = condition;
        this.leftOuter = leftOuter;
        this.innerQualifiers = List.copyOf(innerQualifiers);
        this.schema = JoinSupport.mergeSchemas(outer.getSchema(), inner.getSchema());
        this.evaluator = new ExpressionEvaluator(this.schema);
    }

    @Override
    public void open() {
        inner.open();
        innerRows = new ArrayList<>();
        Row r;
        while ((r = inner.next()) != null) innerRows.add(r);
        inner.close();
        outer.open();
        currentOuter = outer.next();
        innerPos = 0;
        matched = false;
    }

    @Override
    public Row next() {
        while (currentOuter != null) {
            if (innerPos < innerRows.size()) {
                Row joined = JoinSupport.mergeRows(currentOuter, innerRows.get(innerPos++));
                if (condition == null || evaluator.evaluate(condition, joined)) {
                    matched = true;
                    return joined;
                }
                continue;
            }
            Row unmatched = leftOuter && !matched
                    ? JoinSupport.mergeRows(currentOuter, JoinSupport.nullRow(inner.getSchema(), innerQualifiers))
                    : null;
            currentOuter = outer.next();
            innerPos = 0;
            matched = false;
            if (unmatched != null) return unmatched;
        }
        return null;
    }

    @Override
    public void close() {
        currentOuter = null;
        innerRows = null;
        outer.close();
    }

    @Override
    public Schema getSchema() {
        return schema;
    }
}
