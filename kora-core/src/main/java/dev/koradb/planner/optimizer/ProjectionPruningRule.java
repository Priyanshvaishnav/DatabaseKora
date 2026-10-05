package dev.koradb.planner.optimizer;

import dev.koradb.catalog.Catalog;
import dev.koradb.catalog.Schema;
import dev.koradb.planner.logical.*;
import dev.koradb.sql.ast.*;

import java.util.*;

/**
 * Eliminates unreferenced column reads by pushing down projection requirements from
 * the root of the query tree to the {@link LogicalScan} leaves.
 */
public final class ProjectionPruningRule implements Rule {

    @Override
    public LogicalPlanNode apply(LogicalPlanNode node, Catalog catalog) {
        return prune(node, new HashSet<>(), catalog);
    }

    private LogicalPlanNode prune(LogicalPlanNode node, Set<String> requiredCols, Catalog catalog) {
        if (node instanceof LogicalProject project) {
            Set<String> newRequired = new HashSet<>();
            for (SelectItem item : project.projectItems()) {
                if (!item.isAggregate()) {
                    newRequired.add(item.expression());
                }
            }
            LogicalPlanNode newChild = prune(project.child(), newRequired, catalog);
            return new LogicalProject(newChild, project.projectItems());
        }

        if (node instanceof LogicalSort sort) {
            Set<String> newRequired = new HashSet<>(requiredCols);
            for (OrderItem item : sort.orderItems()) {
                newRequired.add(item.columnName());
            }
            LogicalPlanNode newChild = prune(sort.child(), newRequired, catalog);
            return new LogicalSort(newChild, sort.orderItems());
        }

        if (node instanceof LogicalAggregate agg) {
            Set<String> newRequired = new HashSet<>();
            agg.groupBy().ifPresent(newRequired::add);
            for (SelectItem item : agg.selectItems()) {
                if (item.isAggregate() && !item.expression().equals("*")) {
                    newRequired.add(item.expression());
                } else if (!item.isAggregate()) {
                    newRequired.add(item.expression());
                }
            }
            LogicalPlanNode newChild = prune(agg.child(), newRequired, catalog);
            return new LogicalAggregate(newChild, agg.selectItems(), agg.groupBy());
        }

        if (node instanceof LogicalFilter filter) {
            Set<String> newRequired = new HashSet<>(requiredCols);
            for (BinaryExpr pred : filter.predicates()) {
                extractColumns(pred, newRequired);
            }
            LogicalPlanNode newChild = prune(filter.child(), newRequired, catalog);
            return new LogicalFilter(newChild, filter.predicates());
        }

        if (node instanceof LogicalJoin join) {
            Set<String> leftReq = new HashSet<>();
            Set<String> rightReq = new HashSet<>();

            Set<String> totalReq = new HashSet<>(requiredCols);
            if (join.onCondition() != null) {
                extractColumns(join.onCondition(), totalReq);
            }

            for (String col : totalReq) {
                if (matchesSchema(col, join.left().getSchema())) {
                    leftReq.add(col);
                }
                if (matchesSchema(col, join.right().getSchema())) {
                    rightReq.add(col);
                }
            }

            LogicalPlanNode newLeft = prune(join.left(), leftReq, catalog);
            LogicalPlanNode newRight = prune(join.right(), rightReq, catalog);
            return new LogicalJoin(newLeft, newRight, join.onCondition(), join.joinType());
        }

        if (node instanceof LogicalScan scan) {
            if (requiredCols.isEmpty()) {
                // SELECT * without explicit column pruning
                return scan;
            }

            // Extract table-specific required columns
            List<String> pruned = new ArrayList<>();
            for (ColumnDef def : scan.getSchema().columns()) {
                String name = def.name();
                String qualified = scan.tableName() + "." + name;
                if (containsIgnoreCase(requiredCols, name) || containsIgnoreCase(requiredCols, qualified)) {
                    pruned.add(name);
                }
            }

            // Also ensure columns needed for pushedPredicates are retained
            for (BinaryExpr pred : scan.pushedPredicates()) {
                List<String> predCols = new ArrayList<>();
                extractColumns(pred, predCols);
                for (String c : predCols) {
                    String shortName = c.contains(".") ? c.substring(c.indexOf('.') + 1) : c;
                    if (!containsIgnoreCase(pruned, shortName) && scan.getSchema().hasColumn(shortName)) {
                        pruned.add(shortName);
                    }
                }
            }

            if (!pruned.isEmpty() && pruned.size() < scan.getSchema().columns().size()) {
                return scan.withProjectedColumns(pruned);
            }
            return scan;
        }

        // Generic fallback for unary nodes (e.g. LogicalLimit)
        List<LogicalPlanNode> prunedChildren = new ArrayList<>();
        for (LogicalPlanNode child : node.getChildren()) {
            prunedChildren.add(prune(child, requiredCols, catalog));
        }
        return node.withChildren(prunedChildren);
    }

    private static boolean containsIgnoreCase(Collection<String> set, String target) {
        for (String s : set) {
            if (s.equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    private static boolean matchesSchema(String col, Schema schema) {
        if (schema.hasColumn(col)) return true;
        if (col.contains(".")) {
            String shortName = col.substring(col.indexOf('.') + 1);
            return schema.hasColumn(shortName);
        }
        return false;
    }

    private static void extractColumns(Expression expr, Collection<String> cols) {
        if (expr instanceof ColumnRefExpr col) {
            cols.add(col.columnName());
        } else if (expr instanceof BinaryExpr bin) {
            extractColumns(bin.left(), cols);
            extractColumns(bin.right(), cols);
        }
    }
}
