package dev.koradb.sql.ast;

import java.util.List;

/**
 * {@code INSERT INTO <tableName> VALUES (<value>, ...)}
 *
 * <p>In Milestone 1, only positional values are supported — no column list.
 * Values must be supplied in the order the columns were defined in CREATE TABLE.
 *
 * @param tableName the target table name
 * @param values    ordered list of literal values to insert
 */
public record InsertStmt(String tableName, List<LiteralExpr> values)
        implements Statement {

    public InsertStmt {
        values = List.copyOf(values);
    }
}
