package dev.koradb.sql.ast;

import java.util.List;

/**
 * {@code INSERT INTO <table> [(c1, c2, ...)] VALUES (v1, v2, ...) [, (...)]...}
 *
 * @param tableName target table
 * @param columns   named target columns, or null for all columns in table order
 * @param rows      one value list per inserted row
 */
public record InsertStmt(String tableName, List<String> columns, List<List<LiteralExpr>> rows)
        implements Statement {

    public InsertStmt {
        columns = columns == null ? null : List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
    }

    /** Single row into all columns. */
    public InsertStmt(String tableName, List<LiteralExpr> values) {
        this(tableName, null, List.of(values));
    }

    /** Values of the first row (single-row form). */
    public List<LiteralExpr> values() {
        return rows.get(0);
    }
}
