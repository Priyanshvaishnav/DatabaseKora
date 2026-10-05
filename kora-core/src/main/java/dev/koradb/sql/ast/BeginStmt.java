package dev.koradb.sql.ast;

/**
 * AST node for {@code BEGIN [TRANSACTION]}.
 * Starts an explicit transaction.
 */
public record BeginStmt() implements Statement {
}
