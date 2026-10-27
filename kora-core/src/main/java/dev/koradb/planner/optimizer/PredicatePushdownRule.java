package dev.koradb.planner.optimizer;

import dev.koradb.catalog.Catalog;
import dev.koradb.catalog.Schema;
import dev.koradb.planner.logical.*;
import dev.koradb.sql.ast.Expression;
import dev.koradb.sql.ast.ExpressionUtils;

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
        List<Expression> predicates = filter.predicates();

        // 1. Filter over Filter: merge predicates
        if (child instanceof LogicalFilter innerFilter) {
            List<Expression> merged = new ArrayList<>(innerFilter.predicates());
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
            List<Expression> leftPreds = new ArrayList<>();
            List<Expression> rightPreds = new ArrayList<>();
            List<Expression> remainPreds = new ArrayList<>();

            for (Expression pred : predicates) {
                List<String> cols = new ArrayList<>();
                extractColumns(pred, cols);

                boolean canLeft = allColumnsMatch(cols, join.left());
                boolean canRight = allColumnsMatch(cols, join.right());

                if (canLeft && !canRight) {
                    leftPreds.add(pred);
                } else if (canRight && !canLeft && join.joinType() != dev.koradb.planner.logical.JoinType.LEFT) {
                    // Below a LEFT JOIN a right-side predicate would turn non-matches into NULL rows
                    // instead of removing them, so it stays above the join.
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
            List<Expression> merged = new ArrayList<>(scan.pushedPredicates());
            merged.addAll(predicates);
            return scan.withPushedPredicates(merged);
        }

        // Otherwise: recurse into child and return filter
        LogicalPlanNode optimizedChild = apply(child, catalog);
        return new LogicalFilter(optimizedChild, predicates);
    }

    private static void extractColumns(Expression expr, List<String> columns) {
        ExpressionUtils.collectColumns(expr, columns);
    }

    private static boolean allColumnsMatch(List<String> columns, LogicalPlanNode node) {
        if (columns.isEmpty()) {
            return false;
        }
        for (String col : columns) {
            if (!dev.koradb.planner.physical.PhysicalPlanner.columnBelongsToSide(col, node)) {
                return false;
            }
        }
        return true;
    }
}
