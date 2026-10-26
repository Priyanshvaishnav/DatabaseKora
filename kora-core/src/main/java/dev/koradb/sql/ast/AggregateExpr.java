package dev.koradb.sql.ast;

/**
 * An aggregate call inside HAVING, e.g. {@code COUNT(*) > 1}. It is evaluated against the
 * aggregated row, where its value is stored under {@link #key()}.
 */
public record AggregateExpr(AggregateType type, String column) implements Expression {
    /** Name of the aggregated value ("COUNT(*)", "SUM(amount)"), as produced for select items. */
    public String key() {
        return "%s(%s)".formatted(type.name(), column);
    }

    @Override
    public String toString() {
        return key();
    }
}
