package dev.koradb.sql.ast;

/** {@code DROP DATABASE <name>} */
public record DropDatabaseStmt(String databaseName, boolean ifExists) implements Statement {
    public DropDatabaseStmt(String databaseName) {
        this(databaseName, false);
    }
}
