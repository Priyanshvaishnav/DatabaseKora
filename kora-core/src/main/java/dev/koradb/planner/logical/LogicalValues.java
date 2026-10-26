package dev.koradb.planner.logical;

import dev.koradb.catalog.Schema;

import java.util.List;

/** One empty row: the source of {@code SELECT 1} / {@code SELECT 2 * 3} without FROM. */
public final class LogicalValues implements LogicalPlanNode {

    @Override
    public Schema getSchema() {
        return new Schema(List.of());
    }

    @Override
    public List<LogicalPlanNode> getChildren() {
        return List.of();
    }

    @Override
    public LogicalPlanNode withChildren(List<LogicalPlanNode> newChildren) {
        return this;
    }

    @Override
    public void explain(StringBuilder sb, String prefix, boolean isTail) {
        sb.append(prefix).append(isTail ? "└── " : "├── ").append("LogicalValues [one row]\n");
    }

    @Override
    public String toString() {
        return "LogicalValues";
    }
}
