package dev.koradb.planner.optimizer;

import dev.koradb.catalog.Catalog;
import dev.koradb.catalog.Schema;
import dev.koradb.planner.logical.*;
import dev.koradb.sql.ast.BinaryExpr;
import dev.koradb.sql.ast.ColumnRefExpr;
import dev.koradb.sql.ast.Expression;

import java.util.ArrayList;
import java.util.List;

/**
 * Pushes relational selection predicates down as close to the data sources (scans) as possible.
 *
 * <p>Key transformations:
 * <ul>
 *   <li><b>Filter over Join</b>: Predicates that only reference the left relation are pushed
 *       below the left child; predicates that only reference the right relation are pushed below
 *       the right child. This drastically prunes the cardinality of rows entering the join.</li>
 *   <li><b>Filter over Project</b>: Filters are pushed below projections.</li>
 *   <li><b>Filter over Filter</b>: Adjacent filters are flattened and merged.</li>
 *   <li><b>Filter over Scan</b>: Pushes predicates directly into {@link LogicalScan#pushedPredicates()}.</li>
 * </ul>
 */
public final class PredicatePushdownRule implements Rule {

    @Override
    public LogicalPlanNode apply(LogicalPlanNode node, Catalog catalog) {
        if (node instanceof LogicalFilter filter) {
            return pushDownFilter(filter, catalog);
        }

        // Recursively apply to children
        List<LogicalPlanNode> newChildren = new ArrayList<>();
        for (LogicalPlanNode child : node.getChildren()) {
            newChildren.add(apply(child, catalog));
        }
        return node.withChildren(newChildren);
    }

    private LogicalPlanNode pushDownFilter(LogicalFilter filter, Catalog catalog) {
        LogicalPlanNode child = filter.child();
        List<BinaryExpr> predicates = filter.predicates();

        // 1. Filter over Filter: merge predicates
        if (child instanceof LogicalFilter innerFilter) {
            List<BinaryExpr> merged = new ArrayList<>(innerFilter.predicates());
            merged.addAll(predicates);
            return apply(new LogicalFilter(innerFilter.child(), merged), catalog);
        }

        // 2. Filter over Project: push filter below project
        if (child instanceof LogicalProject project) {
            LogicalPlanNode pushed = apply(new LogicalFilter(project.child(), predicates), catalog);
            return new LogicalProject(pushed, project.projectItems());
        }

        // 3. Filter over Join: split predicates to left, right, and join
        if (child instanceof LogicalJoin join) {
            List<BinaryExpr> leftPreds = new ArrayList<>();
            List<BinaryExpr> rightPreds = new ArrayList<>();
            List<BinaryExpr> remainPreds = new ArrayList<>();

            for (BinaryExpr pred : predicates) {
                List<String> cols = new ArrayList<>();
                extractColumns(pred, cols);

                boolean canLeft = allColumnsMatch(cols, join.left());
                boolean canRight = allColumnsMatch(cols, join.right());

                if (canLeft && !canRight) {
                    leftPreds.add(pred);
                } else if (canRight && !canLeft) {
                    rightPreds.add(pred);
                } else {
                    // Predicate references both sides (or neither, e.g. cross-predicate)
                    remainPreds.add(pred);
                }
            }

            LogicalPlanNode newLeft = leftPreds.isEmpty()
                    ? apply(join.left(), catalog)
                    : apply(new LogicalFilter(join.left(), leftPreds), catalog);

            LogicalPlanNode newRight = rightPreds.isEmpty()
                    ? apply(join.right(), catalog)
                    : apply(new LogicalFilter(join.right(), rightPreds), catalog);

            LogicalPlanNode newJoin = new LogicalJoin(newLeft, newRight, join.onCondition(), join.joinType());

            if (remainPreds.isEmpty()) {
                return newJoin;
            }
            return new LogicalFilter(newJoin, remainPreds);
        }

        // 4. Filter over Scan: push predicates directly into LogicalScan
        if (child instanceof LogicalScan scan) {
            List<BinaryExpr> merged = new ArrayList<>(scan.pushedPredicates());
            merged.addAll(predicates);
            return scan.withPushedPredicates(merged);
        }

        // Otherwise: recurse into child and return filter
        LogicalPlanNode optimizedChild = apply(child, catalog);
        return new LogicalFilter(optimizedChild, predicates);
    }

    private static void extractColumns(Expression expr, List<String> columns) {
        if (expr instanceof ColumnRefExpr col) {
            columns.add(col.columnName());
        } else if (expr instanceof BinaryExpr bin) {
            extractColumns(bin.left(), columns);
            extractColumns(bin.right(), columns);
        }
    }

    private static boolean allColumnsMatch(List<String> columns, LogicalPlanNode node) {
        if (columns.isEmpty()) {
            return false;
        }
        Schema schema = node.getSchema();
        String tableName = node instanceof LogicalScan scan ? scan.tableName() : null;

        for (String col : columns) {
            if (col.contains(".")) {
                String prefix = col.substring(0, col.indexOf('.'));
                String name = col.substring(col.indexOf('.') + 1);
                if (tableName != null && !tableName.equalsIgnoreCase(prefix)) {
                    return false;
                }
                if (!schema.hasColumn(name) && !schema.hasColumn(col)) {
                    return false;
                }
            } else {
                if (!schema.hasColumn(col)) {
                    return false;
                }
            }
        }
        return true;
    }
}
