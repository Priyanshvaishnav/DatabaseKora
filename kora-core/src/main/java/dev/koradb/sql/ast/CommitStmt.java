package dev.koradb.sql.ast;

/**
 * AST node for {@code COMMIT [TRANSACTION]}.
 * Commits the currently active transaction.
 */
public record CommitStmt() implements Statement {
}
