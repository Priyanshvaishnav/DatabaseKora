package dev.koradb.execution.operator;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.execution.ExpressionEvaluator;
import dev.koradb.sql.ast.BinaryExpr;
import dev.koradb.sql.ast.WhereClause;

import java.util.List;

/**
 * Filter (Selection) operator.
 *
 * <p>Evaluates WHERE predicates against tuples emitted by the child operator,
 * passing through only those tuples that satisfy all predicates.
 */
public class FilterOperator implements Operator {

    private final Operator child;
    private final List<BinaryExpr> predicates;
    private final ExpressionEvaluator evaluator;

    public FilterOperator(Operator child, WhereClause where, ExpressionEvaluator evaluator) {
        this.child = child;
        this.predicates = (where != null && !where.isEmpty()) ? where.predicates() : List.of();
        this.evaluator = evaluator;
    }

    public FilterOperator(Operator child, List<BinaryExpr> predicates, ExpressionEvaluator evaluator) {
        this.child = child;
        this.predicates = predicates != null ? List.copyOf(predicates) : List.of();
        this.evaluator = evaluator;
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public Row next() {
        Row row;
        while ((row = child.next()) != null) {
            if (matches(row)) {
                return row;
            }
        }
        return null;
    }

    private boolean matches(Row row) {
        if (predicates.isEmpty()) return true;
        for (BinaryExpr pred : predicates) {
            if (!evaluator.evaluate(pred, row)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        child.close();
    }

    @Override
    public Schema getSchema() {
        return child.getSchema();
    }
}
