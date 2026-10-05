package dev.koradb.sql.ast;

/**
 * A JOIN clause in a SELECT statement.
 *
 * <pre>
 *   SELECT ... FROM users JOIN orders ON users.id = orders.user_id
 * </pre>
 *
 * @param tableName   the table being joined
 * @param onCondition the binary expression specifying the join predicate
 */
public record JoinClause(String tableName, BinaryExpr onCondition) {

    public JoinClause {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Joined table name must not be blank");
        }
    }
}
