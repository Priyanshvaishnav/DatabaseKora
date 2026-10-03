package dev.koradb.sql.ast;

/**
 * A reference to a column by name — e.g. {@code age} in {@code WHERE age > 20}.
 *
 * <p>In Milestone 1 column names are unqualified (no table prefix).
 * Multi-table support and qualified names ({@code table.column}) will be
 * added in a later milestone when JOINs are introduced.
 *
 * @param columnName the column name exactly as written in the SQL (case-preserved)
 */
public record ColumnRefExpr(String columnName) implements Expression {

    @Override
    public String toString() {
        return "ColumnRef(%s)".formatted(columnName);
    }
}
