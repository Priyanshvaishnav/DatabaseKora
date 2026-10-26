package dev.koradb.sql.ast;

/**
 * Sealed interface for all expression nodes used in WHERE clauses,
 * SET assignments, and VALUES lists.
 *
 * <p>Permits:
 * <ul>
 *   <li>{@link LiteralExpr}   — a constant value (int, string, double, boolean, null)</li>
 *   <li>{@link ColumnRefExpr} — a reference to a column by name</li>
 *   <li>{@link BinaryExpr}    — comparison, AND/OR, LIKE or arithmetic</li>
 *   <li>{@link UnaryExpr}     — NOT, unary minus, IS [NOT] NULL</li>
 *   <li>{@link InExpr}        — x [NOT] IN (list) / x [NOT] IN (SELECT ...)</li>
 *   <li>{@link BetweenExpr}   — x [NOT] BETWEEN a AND b</li>
 *   <li>{@link SubqueryExpr}  — (SELECT ...) used as a value, or EXISTS (SELECT ...)</li>
 *   <li>{@link AggregateExpr} — COUNT(*) / SUM(x) ... inside HAVING</li>
 * </ul>
 */
public sealed interface Expression
        permits LiteralExpr, ColumnRefExpr, BinaryExpr, UnaryExpr, InExpr, BetweenExpr, SubqueryExpr, AggregateExpr {
}
