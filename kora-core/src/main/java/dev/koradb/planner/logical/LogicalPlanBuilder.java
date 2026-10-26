package dev.koradb.planner.logical;

import dev.koradb.catalog.Catalog;
import dev.koradb.catalog.Table;
import dev.koradb.exception.ExecutionException;
import dev.koradb.sql.ast.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds a canonical initial {@link LogicalPlanNode} tree from a parsed {@link SelectStmt} AST.
 *
 * <pre>
 *   Limit
 *    └ Sort            (after Distinct/Project when it sorts by output names)
 *       └ Distinct
 *          └ Project   (select items; drops hidden HAVING / ORDER BY aggregates)
 *             └ Filter (HAVING)
 *                └ Aggregate (GROUP BY c1, c2 ...)
 *                   └ Filter (WHERE)
 *                      └ Join ... Join (left-deep, INNER or LEFT)
 *                         └ Scan [alias]          or Values (SELECT without FROM)
 * </pre>
 */
public final class LogicalPlanBuilder {

    private LogicalPlanBuilder() {}

    public static LogicalPlanNode build(SelectStmt stmt, Catalog catalog) {
        Objects.requireNonNull(stmt, "SelectStmt must not be null");
        Objects.requireNonNull(catalog, "Catalog must not be null");

        // 1. Source: base scan, or one empty row for SELECT without FROM
        LogicalPlanNode root;
        if (stmt.tableName() == null) {
            root = new LogicalValues();
        } else {
            Table primaryTable = catalog.getTable(stmt.tableName());
            root = new LogicalScan(stmt.tableName(), primaryTable.getSchema(),
                    Optional.ofNullable(stmt.tableAlias()), List.of(), List.of());
        }

        // 2. JOINs, left to right
        for (JoinClause join : stmt.joins()) {
            Table joinTable = catalog.getTable(join.tableName());
            LogicalPlanNode joinScan = new LogicalScan(join.tableName(), joinTable.getSchema(),
                    Optional.ofNullable(join.alias()), List.of(), List.of());
            root = new LogicalJoin(root, joinScan, join.onCondition(), join.left() ? JoinType.LEFT : JoinType.INNER);
        }

        // 3. WHERE filter
        if (stmt.where().isPresent() && !stmt.where().get().isEmpty()) {
            for (Expression p : stmt.where().get().predicates()) {
                List<AggregateExpr> aggs = new ArrayList<>();
                ExpressionUtils.collectAggregates(p, aggs);
                if (!aggs.isEmpty()) {
                    throw new ExecutionException("Aggregate " + aggs.get(0).key() + " is not allowed in WHERE; use HAVING");
                }
            }
            root = new LogicalFilter(root, stmt.where().get().predicates());
        }

        // 4. GROUP BY / aggregates / HAVING
        boolean hasAggregates = stmt.selectItems().stream().anyMatch(SelectItem::isAggregate)
                || !stmt.groupByColumns().isEmpty() || stmt.having().isPresent();
        boolean hiddenItems = false;
        if (hasAggregates) {
            List<SelectItem> items = new ArrayList<>(stmt.selectItems());
            for (SelectItem item : items) {
                if (item.isComputed()) {
                    throw new ExecutionException("Computed expressions are not supported together with GROUP BY or aggregates: " + item.expression());
                }
            }
            if (items.isEmpty()) {
                throw new ExecutionException("SELECT * cannot be used with GROUP BY or aggregates; list the columns");
            }
            // aggregates used only in HAVING or ORDER BY are computed too, then projected away
            List<AggregateExpr> extra = new ArrayList<>();
            stmt.having().ifPresent(h -> ExpressionUtils.collectAggregates(h, extra));
            for (OrderItem o : stmt.orderBy()) {
                AggregateExpr a = parseAggregateKey(o.columnName());
                if (a != null) extra.add(a);
            }
            for (AggregateExpr a : extra) {
                boolean present = items.stream().anyMatch(i -> i.isAggregate()
                        && (i.alias().equalsIgnoreCase(a.key()) || i.expression().equalsIgnoreCase(a.key())));
                if (!present) {
                    items.add(SelectItem.aggregate(a.type(), a.column(), a.key()));
                    hiddenItems = true;
                } else if (items.stream().noneMatch(i -> i.alias().equalsIgnoreCase(a.key()))) {
                    // present under an alias: also expose it under its own name for HAVING/ORDER BY
                    items.add(SelectItem.aggregate(a.type(), a.column(), a.key()));
                    hiddenItems = true;
                }
            }
            root = new LogicalAggregate(root, items, stmt.groupByColumns());
            if (stmt.having().isPresent()) {
                root = new LogicalFilter(root, List.of(stmt.having().get()));
            }
        }

        // 5. Projection, DISTINCT and ORDER BY
        boolean project = hiddenItems || (!hasAggregates && !stmt.isSelectStar());
        List<SelectItem> projectItems = hiddenItems
                ? stmt.selectItems().stream().map(i -> SelectItem.column(i.alias())).toList()
                : stmt.selectItems();
        // ORDER BY sees the input columns, unless it names an output alias / computed item, or the
        // query is DISTINCT or aggregated (then only output columns exist)
        boolean sortAfterProject = stmt.distinct() || stmt.orderBy().stream()
                .anyMatch(o -> namesOutputOnly(o.columnName(), stmt.selectItems()));

        if (!stmt.orderBy().isEmpty() && !sortAfterProject) {
            root = new LogicalSort(root, stmt.orderBy());
        }
        if (project) {
            root = new LogicalProject(root, projectItems);
        }
        if (stmt.distinct()) {
            root = new LogicalDistinct(root);
        }
        if (!stmt.orderBy().isEmpty() && sortAfterProject) {
            root = new LogicalSort(root, stmt.orderBy());
        }

        // 6. LIMIT and OFFSET
        if (stmt.limit().isPresent() || stmt.offset().isPresent()) {
            root = new LogicalLimit(root, stmt.limit(), stmt.offset());
        }

        return root;
    }

    /** "COUNT(*)" -> AggregateExpr, else null. */
    private static AggregateExpr parseAggregateKey(String name) {
        int p = name.indexOf('(');
        if (p <= 0 || !name.endsWith(")")) return null;
        try {
            return new AggregateExpr(AggregateType.valueOf(name.substring(0, p).toUpperCase()), name.substring(p + 1, name.length() - 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True when {@code name} is the alias of an aliased or computed select item (not a source column name). */
    private static boolean namesOutputOnly(String name, List<SelectItem> items) {
        for (SelectItem i : items) {
            if (i.alias().equalsIgnoreCase(name) && (i.isComputed() || !i.alias().equalsIgnoreCase(i.expression()))) {
                return true;
            }
        }
        return false;
    }
}
