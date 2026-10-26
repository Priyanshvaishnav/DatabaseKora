package dev.koradb.planner.logical;

import dev.koradb.catalog.Schema;

import java.util.List;
import java.util.Objects;

/** SELECT DISTINCT: drops rows equal (on the output columns) to an earlier row. */
public final class LogicalDistinct implements LogicalPlanNode {

    private final LogicalPlanNode child;

    public LogicalDistinct(LogicalPlanNode child) {
        this.child = Objects.requireNonNull(child);
    }

    public LogicalPlanNode child() {
        return child;
    }

    @Override
    public Schema getSchema() {
        return child.getSchema();
    }

    @Override
    public List<LogicalPlanNode> getChildren() {
        return List.of(child);
    }

    @Override
    public LogicalPlanNode withChildren(List<LogicalPlanNode> newChildren) {
        return new LogicalDistinct(newChildren.get(0));
    }

    @Override
    public void explain(StringBuilder sb, String prefix, boolean isTail) {
        sb.append(prefix).append(isTail ? "└── " : "├── ").append("LogicalDistinct\n");
        child.explain(sb, prefix + (isTail ? "    " : "│   "), true);
    }

    @Override
    public String toString() {
        return "LogicalDistinct";
    }
}
