package dev.koradb.sql.ast;

/** {@code DROP DATABASE <name>} */
public record DropDatabaseStmt(String databaseName) implements Statement {}
