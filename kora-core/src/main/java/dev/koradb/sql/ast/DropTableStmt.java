package dev.koradb.sql.ast;

/** {@code DROP TABLE <tableName>} */
public record DropTableStmt(String tableName, boolean ifExists) implements Statement {
    public DropTableStmt(String tableName) {
        this(tableName, false);
    }
}
