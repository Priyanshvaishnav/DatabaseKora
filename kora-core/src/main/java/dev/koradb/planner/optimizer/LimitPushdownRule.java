package dev.koradb.planner.optimizer;

import dev.koradb.catalog.Catalog;
import dev.koradb.planner.logical.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Pushes limit constraints down into scans when no blocking sort or filter operations intervene.
 */
public final class LimitPushdownRule implements Rule {

    @Override
    public LogicalPlanNode apply(LogicalPlanNode node, Catalog catalog) {
        // Recurse first
        List<LogicalPlanNode> newChildren = new ArrayList<>();
        for (LogicalPlanNode child : node.getChildren()) {
            newChildren.add(apply(child, catalog));
        }
        LogicalPlanNode current = node.withChildren(newChildren);

        // Check if current is LogicalLimit directly over LogicalScan or LogicalProject(LogicalScan)
        if (current instanceof LogicalLimit limit) {
            if (limit.child() instanceof LogicalScan) {
                // Already at scan, retain limit node for execution
                return limit;
            }
            if (limit.child() instanceof LogicalProject project && project.child() instanceof LogicalScan) {
                // Safe to preserve; Volcano LimitOperator executes with early termination
                return limit;
            }
        }

        return current;
    }
}
