package dev.koradb.sql.ast;

import java.util.List;
import java.util.Optional;

/**
 * {@code UPDATE <tableName> SET <col>=<val> [, ...] [WHERE <condition>]}
 *
 * <p><strong>Warning</strong>: if {@code where} is empty, ALL rows are updated.
 * This mirrors standard SQL semantics; the executor logs a warning in this case.
 *
 * @param tableName   the table to update
 * @param assignments ordered list of column-name → new-value pairs
 * @param where       optional WHERE clause; empty means update all rows
 */
public record UpdateStmt(
        String                tableName,
        List<Assignment>      assignments,
        Optional<WhereClause> where
) implements Statement {

    public UpdateStmt {
        assignments = List.copyOf(assignments);
    }

    /**
     * A single {@code column = value} assignment in a SET clause.
     *
     * @param columnName the column to update
     * @param value      the new value (a literal expression)
     */
    public record Assignment(String columnName, LiteralExpr value) {}
}
