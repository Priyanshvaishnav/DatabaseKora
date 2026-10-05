package dev.koradb.sql.ast;

/**
 * AST node for {@code ROLLBACK [TRANSACTION]}.
 * Aborts the currently active transaction and undoes uncommitted modifications.
 */
public record RollbackStmt() implements Statement {
}
