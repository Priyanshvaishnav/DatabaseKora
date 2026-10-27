package dev.koradb.sql.ast;

/**
 * Metadata statements: {@code SHOW TABLES}, {@code SHOW DATABASES}, {@code DESCRIBE <table>}
 * (also {@code DESC <table>}). {@code tableName} is set only for {@link Kind#COLUMNS}.
 */
public record ShowStmt(Kind kind, String tableName) implements Statement {
    public enum Kind { TABLES, DATABASES, COLUMNS }
}
