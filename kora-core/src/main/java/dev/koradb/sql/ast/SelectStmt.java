package dev.koradb.sql.ast;

import java.util.List;
import java.util.Optional;

/**
 * {@code SELECT <columns> FROM <tableName> [WHERE <condition>]}
 *
 * @param columns    the projected column names; empty list means {@code SELECT *}
 * @param tableName  the source table
 * @param where      the optional WHERE clause; empty if no filtering
 */
public record SelectStmt(
        List<String>      columns,
        String            tableName,
        Optional<WhereClause> where
) implements Statement {

    public SelectStmt {
        columns = List.copyOf(columns);
    }

    /** Returns true if this is a {@code SELECT *} query. */
    public boolean isSelectStar() {
        return columns.isEmpty();
    }
}
