package dev.koradb.sql.ast;

/**
 * Sealed interface for all expression nodes used in WHERE clauses,
 * SET assignments, and VALUES lists.
 *
 * <p>Permits:
 * <ul>
 *   <li>{@link LiteralExpr}   — a constant value (int, string, double, boolean, null)</li>
 *   <li>{@link ColumnRefExpr} — a reference to a column by name</li>
 *   <li>{@link BinaryExpr}    — a binary comparison or logical operation</li>
 * </ul>
 */
public sealed interface Expression
        permits LiteralExpr, ColumnRefExpr, BinaryExpr {
}
