package dev.koradb.sql.ast;

/** {@code DROP TABLE <tableName>} */
public record DropTableStmt(String tableName) implements Statement {}
