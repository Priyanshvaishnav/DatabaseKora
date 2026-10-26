package dev.koradb.planner.optimizer;

import dev.koradb.catalog.Catalog;
import dev.koradb.planner.logical.LogicalFilter;
import dev.koradb.planner.logical.LogicalPlanNode;
import dev.koradb.sql.ast.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Optimizes predicates by:
 * <ul>
 *   <li>Evaluating compile-time constant expressions (e.g. {@code 1 = 1} → TRUE, omitted).</li>
 *   <li>Normalizing predicates to canonical form ({@code <col> <op> <literal>}).</li>
 *   <li>Eliminating redundant {@link LogicalFilter} nodes when all predicates evaluate to TRUE.</li>
 * </ul>
 */
public final class ConstantFoldingRule implements Rule {

    @Override
    public LogicalPlanNode apply(LogicalPlanNode node, Catalog catalog) {
        // 1. Recurse on children first (bottom-up)
        List<LogicalPlanNode> optimizedChildren = new ArrayList<>();
        for (LogicalPlanNode child : node.getChildren()) {
            optimizedChildren.add(apply(child, catalog));
        }
        LogicalPlanNode current = node.withChildren(optimizedChildren);

        // 2. Apply constant folding if node is LogicalFilter
        if (current instanceof LogicalFilter filter) {
            List<Expression> remaining = new ArrayList<>();
            for (Expression pred : filter.predicates()) {
                Expression normalized = pred instanceof BinaryExpr b ? normalize(b) : pred;
                Boolean constantEval = evaluateConstant(normalized);
                if (!Boolean.TRUE.equals(constantEval)) {
                    // not constant, FALSE or UNKNOWN (e.g. 1 = 2, NULL = 1): keep it, it rejects rows
                    remaining.add(normalized);
                }
                // constant TRUE (1 = 1): dropped
            }
            if (remaining.isEmpty()) {
                // All predicates were tautologies, filter can be completely removed!
                return filter.child();
            }
            return new LogicalFilter(filter.child(), remaining);
        }

        return current;
    }

    private static BinaryExpr normalize(BinaryExpr expr) {
        // If literal is on the left and column on the right of a comparison, flip operands
        if (expr.operator().isComparison() && expr.left() instanceof LiteralExpr lit && expr.right() instanceof ColumnRefExpr col) {
            return new BinaryExpr(col, flipOperator(expr.operator()), lit);
        }
        return expr;
    }

    private static Operator flipOperator(Operator op) {
        return switch (op) {
            case LT  -> Operator.GT;
            case LTE -> Operator.GTE;
            case GT  -> Operator.LT;
            case GTE -> Operator.LTE;
            default  -> op;
        };
    }

    /** Value of an expression without columns, aggregates or subqueries; null when not constant or UNKNOWN. */
    private static Boolean evaluateConstant(Expression expr) {
        if (!ExpressionUtils.columns(expr).isEmpty() || ExpressionUtils.hasSubquery(expr)) return null;
        List<AggregateExpr> aggs = new ArrayList<>();
        ExpressionUtils.collectAggregates(expr, aggs);
        if (!aggs.isEmpty()) return null;
        try {
            Object v = new dev.koradb.execution.ExpressionEvaluator(null).eval(expr, new dev.koradb.catalog.Row(new java.util.LinkedHashMap<>()));
            return v instanceof Boolean b ? b : null;
        } catch (RuntimeException e) {
            return null;       // leave errors (type mismatch, division by zero) to execution
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareLiterals(Object v1, Object v2) {
        if (v1 instanceof Number n1 && v2 instanceof Number n2) {
            return Double.compare(n1.doubleValue(), n2.doubleValue());
        }
        if (v1 instanceof Comparable c1 && v2 instanceof Comparable c2
                && v1.getClass().isAssignableFrom(v2.getClass())) {
            return c1.compareTo(c2);
        }
        return Objects.toString(v1).compareTo(Objects.toString(v2));
    }
}
