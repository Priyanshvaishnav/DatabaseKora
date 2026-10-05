package dev.koradb.planner.logical;

import dev.koradb.catalog.Schema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Logical node representing pagination (LIMIT and OFFSET).
 */
public final class LogicalLimit implements LogicalPlanNode {

    private final LogicalPlanNode child;
    private final Optional<Integer> limit;
    private final Optional<Integer> offset;

    public LogicalLimit(LogicalPlanNode child, Optional<Integer> limit, Optional<Integer> offset) {
        this.child = Objects.requireNonNull(child);
        this.limit = limit != null ? limit : Optional.empty();
        this.offset = offset != null ? offset : Optional.empty();
    }

    public LogicalPlanNode child() {
        return child;
    }

    public Optional<Integer> limit() {
        return limit;
    }

    public Optional<Integer> offset() {
        return offset;
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
        if (newChildren.size() != 1) {
            throw new IllegalArgumentException("LogicalLimit must have exactly 1 child, got: " + newChildren.size());
        }
        return new LogicalLimit(newChildren.get(0), limit, offset);
    }

    @Override
    public void explain(StringBuilder sb, String prefix, boolean isTail) {
        sb.append(prefix).append(isTail ? "└── " : "├── ")
                .append("LogicalLimit [limit=").append(limit.map(Object::toString).orElse("ALL"))
                .append(", offset=").append(offset.orElse(0)).append("]\n");
        child.explain(sb, prefix + (isTail ? "    " : "│   "), true);
    }

    @Override
    public String toString() {
        return "LogicalLimit(limit=" + limit.orElse(-1) + ", offset=" + offset.orElse(0) + ")";
    }
}
