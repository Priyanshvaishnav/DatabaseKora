package dev.koradb.planner.logical;

import dev.koradb.catalog.Schema;
import dev.koradb.sql.ast.BinaryExpr;

import java.util.List;
import java.util.Objects;

/**
 * Logical node representing a relational selection (filtering rows based on predicates).
 */
public final class LogicalFilter implements LogicalPlanNode {

    private final LogicalPlanNode child;
    private final List<BinaryExpr> predicates;

    public LogicalFilter(LogicalPlanNode child, List<BinaryExpr> predicates) {
        this.child = Objects.requireNonNull(child);
        this.predicates = predicates != null ? List.copyOf(predicates) : List.of();
    }

    public LogicalPlanNode child() {
        return child;
    }

    public List<BinaryExpr> predicates() {
        return predicates;
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
            throw new IllegalArgumentException("LogicalFilter must have exactly 1 child, got: " + newChildren.size());
        }
        return new LogicalFilter(newChildren.get(0), predicates);
    }

    @Override
    public void explain(StringBuilder sb, String prefix, boolean isTail) {
        sb.append(prefix).append(isTail ? "└── " : "├── ").append("LogicalFilter [predicates=").append(predicates).append("]\n");
        child.explain(sb, prefix + (isTail ? "    " : "│   "), true);
    }

    @Override
    public String toString() {
        return "LogicalFilter(" + predicates + ")";
    }
}
