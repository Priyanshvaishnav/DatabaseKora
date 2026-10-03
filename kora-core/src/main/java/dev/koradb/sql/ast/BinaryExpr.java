package dev.koradb.sql.ast;

/**
 * A binary expression — a left operand, an operator, and a right operand.
 *
 * <p>In Milestone 1 this is exclusively used for predicates:
 * <pre>
 *   age > 20
 *   id = 1
 *   name != 'Bob'
 * </pre>
 *
 * <p>Both {@code left} and {@code right} are {@link Expression} implementations,
 * which means nested expressions (e.g. {@code (a + b) > c}) are structurally
 * supported even though arithmetic is not yet parsed.
 *
 * @param left     the left-hand operand
 * @param operator the comparison operator
 * @param right    the right-hand operand
 */
public record BinaryExpr(Expression left, Operator operator, Expression right)
        implements Expression {

    @Override
    public String toString() {
        return "BinaryExpr(%s %s %s)".formatted(left, operator, right);
    }
}
