package dev.koradb.sql.ast;

/** {@code ALTER TABLE <name> ADD [COLUMN] <col> <type> [NOT NULL]}; existing rows read NULL for the new column. */
public record AlterTableStmt(String tableName, ColumnDef addColumn) implements Statement {}
