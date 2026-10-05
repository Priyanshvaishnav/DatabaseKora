package dev.koradb.execution.operator;

import dev.koradb.catalog.Catalog;
import dev.koradb.catalog.DiskTable;
import dev.koradb.catalog.Table;
import dev.koradb.execution.ExpressionEvaluator;
import dev.koradb.index.Index;
import dev.koradb.index.IndexKey;
import dev.koradb.sql.ast.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a {@link SelectStmt} AST node into an executable {@link Operator} tree.
 */
public final class PhysicalPlanBuilder {

    private PhysicalPlanBuilder() {}

    /**
     * Builds the operator tree pipeline for a given SELECT statement.
     */
    public static Operator build(SelectStmt stmt, Catalog catalog) {
        Table table = catalog.getTable(stmt.tableName());
        Operator root = buildScan(stmt, table, catalog);

        // 1. Optional JOIN
        if (stmt.join().isPresent()) {
            JoinClause join = stmt.join().get();
            Table innerTable = catalog.getTable(join.tableName());
            Operator innerScan = new SeqScanOperator(innerTable);

            BinaryExpr on = join.onCondition();
            if (on.operator() == dev.koradb.sql.ast.Operator.EQ
                    && on.left() instanceof ColumnRefExpr lref
                    && on.right() instanceof ColumnRefExpr rref) {
                root = new HashJoinOperator(root, innerScan, lref.columnName(), rref.columnName());
            } else {
                root = new NestedLoopJoinOperator(root, innerScan, on);
            }
        }

        // 2. Optional WHERE filter (for non-indexed or remaining predicates)
        if (stmt.where().isPresent() && !(root instanceof FilterOperator)) {
            WhereClause where = stmt.where().get();
            if (!where.isEmpty()) {
                root = new FilterOperator(root, where, new ExpressionEvaluator(root.getSchema()));
            }
        }

        // 3. Optional GROUP BY and Aggregations
        boolean hasAggregates = stmt.selectItems().stream().anyMatch(SelectItem::isAggregate)
                || stmt.groupBy().isPresent();
        if (hasAggregates) {
            root = new AggregateOperator(root, stmt.selectItems(), stmt.groupBy().orElse(null));
        }

        // 4. Optional ORDER BY
        if (!stmt.orderBy().isEmpty()) {
            root = new SortOperator(root, stmt.orderBy());
        }

        // 5. Projection (only if not already aggregated and not SELECT *)
        if (!hasAggregates && !stmt.isSelectStar()) {
            root = new ProjectOperator(root, stmt.selectItems());
        }

        // 6. Optional LIMIT & OFFSET
        if (stmt.limit().isPresent() || stmt.offset().isPresent()) {
            int limit = stmt.limit().orElse(-1);
            int offset = stmt.offset().orElse(0);
            root = new LimitOperator(root, limit, offset);
        }

        return root;
    }

    private static Operator buildScan(SelectStmt stmt, Table table, Catalog catalog) {
        if (stmt.where().isPresent() && table instanceof DiskTable diskTable) {
            WhereClause where = stmt.where().get();
            for (int i = 0; i < where.predicates().size(); i++) {
                BinaryExpr bin = where.predicates().get(i);
                if (bin.operator() == dev.koradb.sql.ast.Operator.EQ) {
                    String col = null;
                    Object val = null;
                    if (bin.left() instanceof ColumnRefExpr cref && bin.right() instanceof LiteralExpr lit) {
                        col = cref.columnName();
                        val = lit.value();
                    } else if (bin.right() instanceof ColumnRefExpr cref && bin.left() instanceof LiteralExpr lit) {
                        col = cref.columnName();
                        val = lit.value();
                    }

                    if (col != null && val != null) {
                        var indexOpt = catalog.getIndexForColumn(stmt.tableName(), col);
                        if (indexOpt.isPresent()) {
                            Index index = indexOpt.get();
                            IndexKey key = new IndexKey(val, index.getKeyType());
                            Operator scan = IndexScanOperator.point(
                                    diskTable.getTableFile(), table, index, key);

                            // Remaining unindexed predicates
                            List<BinaryExpr> remaining = new ArrayList<>(where.predicates());
                            remaining.remove(i);
                            if (!remaining.isEmpty()) {
                                return new FilterOperator(scan, remaining, new ExpressionEvaluator(scan.getSchema()));
                            }
                            return scan;
                        }
                    }
                }
            }
        }

        return new SeqScanOperator(table);
    }
}
