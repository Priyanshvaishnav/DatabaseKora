package dev.koradb.sql.ast;

/**
 * An item in the SELECT projection list.
 *
 * <p>Can represent either a simple column reference (e.g. {@code name}),
 * or an aggregate expression (e.g. {@code COUNT(*)}, {@code SUM(price)}),
 * optionally aliased with {@code AS alias}.
 *
 * @param expression      raw expression text (e.g. {@code "name"}, {@code "COUNT(*)"})
 * @param alias           output column name (defaults to expression if AS omitted)
 * @param aggregateType   aggregate function type, or null if simple column
 * @param aggregateColumn column passed into aggregate function (or "*" for COUNT(*)), or null
 */
public record SelectItem(
        String expression,
        String alias,
        AggregateType aggregateType,
        String aggregateColumn
) {
    public SelectItem {
        if (alias == null || alias.isBlank()) {
            alias = expression;
        }
    }

    /** Simple column constructor. */
    public static SelectItem column(String columnName) {
        return new SelectItem(columnName, columnName, null, null);
    }

    /** Simple column constructor with alias. */
    public static SelectItem column(String columnName, String alias) {
        return new SelectItem(columnName, alias, null, null);
    }

    /** Aggregate function constructor. */
    public static SelectItem aggregate(AggregateType type, String aggregateCol, String alias) {
        String expr = "%s(%s)".formatted(type.name(), aggregateCol);
        return new SelectItem(expr, alias != null ? alias : expr, type, aggregateCol);
    }

    /** True if this select item is an aggregate function. */
    public boolean isAggregate() {
        return aggregateType != null;
    }
}
