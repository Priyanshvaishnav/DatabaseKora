package dev.koradb.planner.logical;

import dev.koradb.catalog.Schema;
import dev.koradb.sql.ast.OrderItem;

import java.util.List;
import java.util.Objects;

/**
 * Logical node representing sorting of rows (ORDER BY).
 */
public final class LogicalSort implements LogicalPlanNode {

    private final LogicalPlanNode child;
    private final List<OrderItem> orderItems;

    public LogicalSort(LogicalPlanNode child, List<OrderItem> orderItems) {
        this.child = Objects.requireNonNull(child);
        this.orderItems = List.copyOf(Objects.requireNonNull(orderItems));
    }

    public LogicalPlanNode child() {
        return child;
    }

    public List<OrderItem> orderItems() {
        return orderItems;
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
            throw new IllegalArgumentException("LogicalSort must have exactly 1 child, got: " + newChildren.size());
        }
        return new LogicalSort(newChildren.get(0), orderItems);
    }

    @Override
    public void explain(StringBuilder sb, String prefix, boolean isTail) {
        sb.append(prefix).append(isTail ? "└── " : "├── ")
                .append("LogicalSort [orderBy=").append(orderItems).append("]\n");
        child.explain(sb, prefix + (isTail ? "    " : "│   "), true);
    }

    @Override
    public String toString() {
        return "LogicalSort(" + orderItems + ")";
    }
}
