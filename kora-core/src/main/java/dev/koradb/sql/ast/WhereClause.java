package dev.koradb.sql.ast;

import java.util.List;

/**
 * The WHERE clause of a SELECT, UPDATE, or DELETE statement.
 *
 * <p>In Milestone 1, multiple predicates are implicitly AND-joined.
 * {@code WHERE age > 20 AND active = true} produces two predicates in the list.
 *
 * <p>Design note: AND is structural (a list) rather than a BinaryExpr node.
 * This keeps {@link dev.koradb.execution.ExpressionEvaluator} simple — it
 * just checks that all predicates are satisfied. OR and NOT will require
 * a full expression tree and are deferred to a later milestone.
 *
 * @param predicates one or more comparison predicates; never empty if this
 *                   clause exists (a missing WHERE is represented by an
 *                   empty Optional in the parent statement)
 */
public record WhereClause(List<BinaryExpr> predicates) {

    public WhereClause {
        predicates = List.copyOf(predicates); // defensive copy, immutable
    }

    /** True if this WHERE clause has no predicates (matches all rows). */
    public boolean isEmpty() {
        return predicates.isEmpty();
    }
}
