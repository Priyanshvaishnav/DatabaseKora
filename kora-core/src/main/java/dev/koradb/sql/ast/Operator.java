package dev.koradb.sql.ast;

/**
 * Binary comparison and logical operators.
 *
 * <p>In Milestone 1, only comparison operators are used in predicates.
 * {@code AND} is handled structurally in {@link WhereClause} as a list of
 * predicates — it is not represented as a BinaryExpr operator. This keeps
 * the evaluator simple and avoids building a full expression tree for M1.
 */
public enum Operator {
    EQ,   // =
    NEQ,  // !=
    LT,   // <
    GT,   // >
    LTE,  // <=
    GTE   // >=
}
