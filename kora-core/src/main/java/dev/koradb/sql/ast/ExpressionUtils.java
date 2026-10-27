package dev.koradb.sql.ast;

import java.util.ArrayList;
import java.util.List;

/** Helpers for walking expression trees. */
public final class ExpressionUtils {

    private ExpressionUtils() {}

    /** Column names referenced by {@code expr} (subqueries are not entered: they are uncorrelated). */
    public static void collectColumns(Expression expr, List<String> out) {
        switch (expr) {
            case ColumnRefExpr c -> out.add(c.columnName());
            case BinaryExpr b -> {
                collectColumns(b.left(), out);
                collectColumns(b.right(), out);
            }
            case UnaryExpr u -> collectColumns(u.operand(), out);
            case InExpr in -> {
                collectColumns(in.operand(), out);
                if (in.list() != null) in.list().forEach(e -> collectColumns(e, out));
            }
            case BetweenExpr bt -> {
                collectColumns(bt.operand(), out);
                collectColumns(bt.low(), out);
                collectColumns(bt.high(), out);
            }
            case AggregateExpr a -> { if (!"*".equals(a.column())) out.add(a.column()); }
            case LiteralExpr l -> { }
            case SubqueryExpr s -> { }
        }
    }

    public static List<String> columns(Expression expr) {
        List<String> out = new ArrayList<>();
        collectColumns(expr, out);
        return out;
    }

    /** Aggregate calls in {@code expr} (HAVING). */
    public static void collectAggregates(Expression expr, List<AggregateExpr> out) {
        switch (expr) {
            case AggregateExpr a -> out.add(a);
            case BinaryExpr b -> {
                collectAggregates(b.left(), out);
                collectAggregates(b.right(), out);
            }
            case UnaryExpr u -> collectAggregates(u.operand(), out);
            case InExpr in -> {
                collectAggregates(in.operand(), out);
                if (in.list() != null) in.list().forEach(e -> collectAggregates(e, out));
            }
            case BetweenExpr bt -> {
                collectAggregates(bt.operand(), out);
                collectAggregates(bt.low(), out);
                collectAggregates(bt.high(), out);
            }
            default -> { }
        }
    }

    /** True when {@code expr} contains a subquery. */
    public static boolean hasSubquery(Expression expr) {
        return switch (expr) {
            case SubqueryExpr s -> true;
            case InExpr in -> in.subquery() != null || hasSubquery(in.operand())
                    || (in.list() != null && in.list().stream().anyMatch(ExpressionUtils::hasSubquery));
            case BinaryExpr b -> hasSubquery(b.left()) || hasSubquery(b.right());
            case UnaryExpr u -> hasSubquery(u.operand());
            case BetweenExpr bt -> hasSubquery(bt.operand()) || hasSubquery(bt.low()) || hasSubquery(bt.high());
            default -> false;
        };
    }
}
