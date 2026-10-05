package dev.koradb.planner.logical;

import dev.koradb.catalog.Catalog;
import dev.koradb.catalog.Table;
import dev.koradb.sql.ast.JoinClause;
import dev.koradb.sql.ast.SelectItem;
import dev.koradb.sql.ast.SelectStmt;

import java.util.Objects;

/**
 * Builds a canonical initial {@link LogicalPlanNode} tree from a parsed {@link SelectStmt} AST.
 */
public final class LogicalPlanBuilder {

    private LogicalPlanBuilder() {}

    /**
     * Translates a {@link SelectStmt} AST into an initial unoptimized logical plan.
     *
     * @param stmt    the parsed SELECT statement
     * @param catalog the catalog for resolving tables and schemas
     * @return the root node of the logical plan tree
     */
    public static LogicalPlanNode build(SelectStmt stmt, Catalog catalog) {
        Objects.requireNonNull(stmt, "SelectStmt must not be null");
        Objects.requireNonNull(catalog, "Catalog must not be null");

        // 1. Base Scan
        Table primaryTable = catalog.getTable(stmt.tableName());
        LogicalPlanNode root = new LogicalScan(stmt.tableName(), primaryTable.getSchema());

        // 2. JOIN
        if (stmt.join().isPresent()) {
            JoinClause join = stmt.join().get();
            Table joinTable = catalog.getTable(join.tableName());
            LogicalPlanNode joinScan = new LogicalScan(join.tableName(), joinTable.getSchema());
            root = new LogicalJoin(root, joinScan, join.onCondition(), JoinType.INNER);
        }

        // 3. WHERE filter
        if (stmt.where().isPresent() && !stmt.where().get().isEmpty()) {
            root = new LogicalFilter(root, stmt.where().get().predicates());
        }

        // 4. GROUP BY and Aggregations
        boolean hasAggregates = stmt.selectItems().stream().anyMatch(SelectItem::isAggregate)
                || stmt.groupBy().isPresent();
        if (hasAggregates) {
            root = new LogicalAggregate(root, stmt.selectItems(), stmt.groupBy());
        }

        // 5. ORDER BY
        if (!stmt.orderBy().isEmpty()) {
            root = new LogicalSort(root, stmt.orderBy());
        }

        // 6. Projection (if not already aggregated and not SELECT *)
        if (!hasAggregates && !stmt.isSelectStar()) {
            root = new LogicalProject(root, stmt.selectItems());
        }

        // 7. LIMIT and OFFSET
        if (stmt.limit().isPresent() || stmt.offset().isPresent()) {
            root = new LogicalLimit(root, stmt.limit(), stmt.offset());
        }

        return root;
    }
}
