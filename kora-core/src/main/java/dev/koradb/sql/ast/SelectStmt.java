package dev.koradb.sql.ast;

import java.util.List;
import java.util.Optional;

/**
 * {@code SELECT <items> FROM <tableName> [JOIN ...] [WHERE ...] [GROUP BY ...] [ORDER BY ...] [LIMIT ... [OFFSET ...]]}
 *
 * @param selectItems projected items (columns or aggregate functions); empty list means {@code SELECT *}
 * @param tableName   the source table
 * @param join        optional JOIN clause
 * @param where       optional WHERE clause; empty if no filtering
 * @param groupBy     optional GROUP BY column name
 * @param orderBy     ordered list of sorting items
 * @param limit       optional LIMIT count
 * @param offset      optional OFFSET count
 */
public record SelectStmt(
        List<SelectItem>      selectItems,
        String                tableName,
        Optional<JoinClause>  join,
        Optional<WhereClause> where,
        Optional<String>      groupBy,
        List<OrderItem>       orderBy,
        Optional<Integer>     limit,
        Optional<Integer>     offset
) implements Statement {

    public SelectStmt {
        selectItems = selectItems != null ? List.copyOf(selectItems) : List.of();
        orderBy     = orderBy != null ? List.copyOf(orderBy) : List.of();
        join        = join != null ? join : Optional.empty();
        where       = where != null ? where : Optional.empty();
        groupBy     = groupBy != null ? groupBy : Optional.empty();
        limit       = limit != null ? limit : Optional.empty();
        offset      = offset != null ? offset : Optional.empty();
    }

    /** Backward-compatible constructor for simple queries without joins, aggregates, or limits. */
    public SelectStmt(List<String> columns, String tableName, Optional<WhereClause> where) {
        this(
                columns.stream().map(SelectItem::column).toList(),
                tableName,
                Optional.empty(),
                where,
                Optional.empty(),
                List.of(),
                Optional.empty(),
                Optional.empty()
        );
    }

    /** Returns projected column names or aliases for backward compatibility. */
    public List<String> columns() {
        return selectItems.stream().map(SelectItem::alias).toList();
    }

    /** Returns true if this is a {@code SELECT *} query. */
    public boolean isSelectStar() {
        return selectItems.isEmpty();
    }
}
