package dev.koradb.sql.ast;

/**
 * AST node for {@code EXPLAIN [ANALYZE] <statement>}.
 *
 * @param statement the statement to explain (typically {@link SelectStmt})
 * @param analyze   whether to execute the plan and measure actual runtime profile
 */
public record ExplainStmt(Statement statement, boolean analyze) implements Statement {
    public ExplainStmt(Statement statement) {
        this(statement, false);
    }
}
