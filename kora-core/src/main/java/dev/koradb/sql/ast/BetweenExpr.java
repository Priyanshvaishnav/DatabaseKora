package dev.koradb.sql.ast;

/** {@code x [NOT] BETWEEN low AND high} (inclusive). */
public record BetweenExpr(Expression operand, Expression low, Expression high, boolean negated) implements Expression {
    @Override
    public String toString() {
        return operand + (negated ? " NOT BETWEEN " : " BETWEEN ") + low + " AND " + high;
    }
}
