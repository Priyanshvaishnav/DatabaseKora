package dev.koradb.sql.ast;

import java.util.List;

/**
 * {@code CREATE TABLE <tableName> (<columnDef>, ...)}
 *
 * @param tableName   the name of the table to create
 * @param columnDefs  ordered list of column definitions; must not be empty
 */
public record CreateTableStmt(String tableName, List<ColumnDef> columnDefs)
        implements Statement {

    public CreateTableStmt {
        columnDefs = List.copyOf(columnDefs);
    }
}
