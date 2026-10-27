package dev.koradb.sql.ast;

import java.util.List;

/**
 * The WHERE clause of a SELECT, UPDATE, or DELETE statement.
 *
 * <p>The top-level AND chain is kept as a list of conjuncts: {@code WHERE age > 20 AND
 * (city = 'Pune' OR city = 'Delhi')} produces two predicates. This keeps predicate pushdown and
 * index selection simple; each conjunct may be any boolean expression (OR, NOT, IN, LIKE, ...).
 *
 * @param predicates one or more boolean expressions, all of which must hold; never empty if this
 *                   clause exists (a missing WHERE is represented by an
 *                   empty Optional in the parent statement)
 */
public record WhereClause(List<Expression> predicates) {

    public WhereClause {
        predicates = List.copyOf(predicates); // defensive copy, immutable
    }

    /** True if this WHERE clause has no predicates (matches all rows). */
    public boolean isEmpty() {
        return predicates.isEmpty();
    }
}
