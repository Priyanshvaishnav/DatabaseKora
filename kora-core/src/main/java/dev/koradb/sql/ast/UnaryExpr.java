package dev.koradb.sql.ast;

/** {@code NOT x}, {@code -x}, {@code x IS NULL}, {@code x IS NOT NULL}. */
public record UnaryExpr(Kind kind, Expression operand) implements Expression {
    public enum Kind { NOT, NEGATE, IS_NULL, IS_NOT_NULL }

    @Override
    public String toString() {
        return switch (kind) {
            case NOT -> "NOT (" + operand + ")";
            case NEGATE -> "-(" + operand + ")";
            case IS_NULL -> operand + " IS NULL";
            case IS_NOT_NULL -> operand + " IS NOT NULL";
        };
    }
}
