package dev.koradb.sql.ast;

/** {@code CREATE DATABASE <name>} */
public record CreateDatabaseStmt(String databaseName, boolean ifNotExists) implements Statement {
    public CreateDatabaseStmt(String databaseName) {
        this(databaseName, false);
    }
}
