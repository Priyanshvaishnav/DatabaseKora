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
    GTE,  // >=
    // boolean connectives (WHERE a OR b, nested AND inside OR)
    AND,
    OR,
    // pattern matching: % = any sequence, _ = one character
    LIKE,
    NOT_LIKE,
    // arithmetic
    ADD,  // +
    SUB,  // -
    MUL,  // *
    DIV,  // /
    MOD;  // %

    /** True for =, !=, <, >, <=, >=. */
    public boolean isComparison() {
        return switch (this) {
            case EQ, NEQ, LT, GT, LTE, GTE -> true;
            default -> false;
        };
    }

    /** True for +, -, *, /, %. */
    public boolean isArithmetic() {
        return switch (this) {
            case ADD, SUB, MUL, DIV, MOD -> true;
            default -> false;
        };
    }

    /** SQL spelling. */
    public String symbol() {
        return switch (this) {
            case EQ -> "=";
            case NEQ -> "!=";
            case LT -> "<";
            case GT -> ">";
            case LTE -> "<=";
            case GTE -> ">=";
            case AND -> "AND";
            case OR -> "OR";
            case LIKE -> "LIKE";
            case NOT_LIKE -> "NOT LIKE";
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
            case MOD -> "%";
        };
    }
}
