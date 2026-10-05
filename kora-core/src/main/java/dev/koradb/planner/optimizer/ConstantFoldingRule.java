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
            List<BinaryExpr> remaining = new ArrayList<>();
            for (BinaryExpr pred : filter.predicates()) {
                BinaryExpr normalized = normalize(pred);
                Boolean constantEval = evaluateConstant(normalized);
                if (constantEval == null) {
                    // Not a constant comparison — keep it
                    remaining.add(normalized);
                } else if (Boolean.TRUE.equals(constantEval)) {
                    // Tautology (e.g. 1 = 1) — drop it
                } else {
                    // Contradiction (e.g. 1 = 2) — keep the contradiction
                    remaining.add(normalized);
                }
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
        // If literal is on the left and column on the right, flip operands
        if (expr.left() instanceof LiteralExpr lit && expr.right() instanceof ColumnRefExpr col) {
            Operator flippedOp = flipOperator(expr.operator());
            if (flippedOp != null) {
                return new BinaryExpr(col, flippedOp, lit);
            }
        }
        return expr;
    }

    private static Operator flipOperator(Operator op) {
        return switch (op) {
            case EQ  -> Operator.EQ;
            case NEQ -> Operator.NEQ;
            case LT  -> Operator.GT;
            case LTE -> Operator.GTE;
            case GT  -> Operator.LT;
            case GTE -> Operator.LTE;
        };
    }

    private static Boolean evaluateConstant(BinaryExpr expr) {
        if (expr.left() instanceof LiteralExpr l && expr.right() instanceof LiteralExpr r) {
            Object v1 = l.value();
            Object v2 = r.value();
            if (v1 == null || v2 == null) {
                return expr.operator() == Operator.NEQ ? Boolean.TRUE : Boolean.FALSE;
            }

            int cmp = compareLiterals(v1, v2);
            return switch (expr.operator()) {
                case EQ  -> cmp == 0;
                case NEQ -> cmp != 0;
                case LT  -> cmp < 0;
                case LTE -> cmp <= 0;
                case GT  -> cmp > 0;
                case GTE -> cmp >= 0;
            };
        }
        return null;
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
