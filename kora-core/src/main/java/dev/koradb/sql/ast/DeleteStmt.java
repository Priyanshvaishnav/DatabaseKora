package dev.koradb.sql.ast;

import java.util.Optional;

/**
 * {@code DELETE FROM <tableName> [WHERE <condition>]}
 *
 * <p><strong>Warning</strong>: if {@code where} is empty, ALL rows are deleted.
 * The executor logs a warning in this case.
 *
 * @param tableName the table to delete from
 * @param where     optional WHERE clause; empty means delete all rows
 */
public record DeleteStmt(String tableName, Optional<WhereClause> where)
        implements Statement {}
