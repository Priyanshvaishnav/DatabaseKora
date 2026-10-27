package dev.koradb.sql.ast;

import java.util.List;
import java.util.Optional;

/**
 * {@code SELECT [DISTINCT] <items> [FROM <table> [alias] [[LEFT|INNER] JOIN <table> [alias] ON <cond>]...]
 * [WHERE ...] [GROUP BY c1, c2 ...] [HAVING ...] [ORDER BY ...] [LIMIT ... [OFFSET ...]]}
 *
 * @param selectItems    projected items; empty list means {@code SELECT *}
 * @param tableName      the source table, or null for {@code SELECT 1} without FROM
 * @param tableAlias     alias of the source table (null when none)
 * @param joins          JOIN clauses, applied left to right
 * @param where          optional WHERE clause
 * @param groupByColumns GROUP BY columns (empty when none)
 * @param having         optional HAVING condition (may use aggregates)
 * @param orderBy        ordered list of sorting items
 * @param limit          optional LIMIT count
 * @param offset         optional OFFSET count
 * @param distinct       SELECT DISTINCT
 */
public record SelectStmt(
        List<SelectItem>      selectItems,
        String                tableName,
        String                tableAlias,
        List<JoinClause>      joins,
        Optional<WhereClause> where,
        List<String>          groupByColumns,
        Optional<Expression>  having,
        List<OrderItem>       orderBy,
        Optional<Integer>     limit,
        Optional<Integer>     offset,
        boolean               distinct
) implements Statement {

    public SelectStmt {
        selectItems    = selectItems != null ? List.copyOf(selectItems) : List.of();
        joins          = joins != null ? List.copyOf(joins) : List.of();
        groupByColumns = groupByColumns != null ? List.copyOf(groupByColumns) : List.of();
        orderBy        = orderBy != null ? List.copyOf(orderBy) : List.of();
        where          = where != null ? where : Optional.empty();
        having         = having != null ? having : Optional.empty();
        limit          = limit != null ? limit : Optional.empty();
        offset         = offset != null ? offset : Optional.empty();
    }

    /** Constructor of the earlier single-JOIN / single-GROUP-BY form. */
    public SelectStmt(List<SelectItem> selectItems, String tableName, Optional<JoinClause> join,
                      Optional<WhereClause> where, Optional<String> groupBy, List<OrderItem> orderBy,
                      Optional<Integer> limit, Optional<Integer> offset) {
        this(selectItems, tableName, null,
                join != null && join.isPresent() ? List.of(join.get()) : List.of(),
                where,
                groupBy != null && groupBy.isPresent() ? List.of(groupBy.get()) : List.of(),
                Optional.empty(), orderBy, limit, offset, false);
    }

    /** Backward-compatible constructor for simple queries without joins, aggregates, or limits. */
    public SelectStmt(List<String> columns, String tableName, Optional<WhereClause> where) {
        this(columns.stream().map(SelectItem::column).toList(), tableName, Optional.empty(), where,
                Optional.empty(), List.of(), Optional.empty(), Optional.empty());
    }

    /** The first JOIN, if any. */
    public Optional<JoinClause> join() {
        return joins.isEmpty() ? Optional.empty() : Optional.of(joins.get(0));
    }

    /** The first GROUP BY column, if any. */
    public Optional<String> groupBy() {
        return groupByColumns.isEmpty() ? Optional.empty() : Optional.of(groupByColumns.get(0));
    }

    /** Name rows of the source table are qualified with: the alias, or the table name. */
    public String sourceName() {
        return tableAlias != null ? tableAlias : tableName;
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
