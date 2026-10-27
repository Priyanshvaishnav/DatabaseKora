package dev.koradb.sql.ast;

/**
 * A subquery used as a value: {@code (SELECT MAX(x) FROM t)} must return at most one row with one
 * column (no row = NULL). With {@code exists = true} it is {@code EXISTS (SELECT ...)}: true when the
 * subquery returns any row. Subqueries are uncorrelated (they cannot refer to the outer row).
 */
public record SubqueryExpr(SelectStmt query, boolean exists) implements Expression {
    @Override
    public String toString() {
        return (exists ? "EXISTS" : "") + "(subquery)";
    }
}
