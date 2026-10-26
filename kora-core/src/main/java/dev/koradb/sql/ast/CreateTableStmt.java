package dev.koradb.sql.ast;

import java.util.List;

/**
 * {@code CREATE TABLE [IF NOT EXISTS] <name> ( <col> <type> [NOT NULL] [PRIMARY KEY] [UNIQUE], ...
 * [, PRIMARY KEY (col)] [, UNIQUE (col)] )}
 *
 * @param primaryKey    the PRIMARY KEY column (NOT NULL + unique index), or null
 * @param uniqueColumns columns with a UNIQUE constraint (unique index)
 * @param ifNotExists   do nothing when the table already exists
 */
public record CreateTableStmt(String tableName, List<ColumnDef> columnDefs, String primaryKey,
                              List<String> uniqueColumns, boolean ifNotExists)
        implements Statement {

    public CreateTableStmt {
        columnDefs = List.copyOf(columnDefs);
        uniqueColumns = uniqueColumns == null ? List.of() : List.copyOf(uniqueColumns);
    }

    public CreateTableStmt(String tableName, List<ColumnDef> columnDefs) {
        this(tableName, columnDefs, null, List.of(), false);
    }
}
