package dev.koradb.planner.logical;

import dev.koradb.catalog.Schema;
import dev.koradb.sql.ast.Expression;
import dev.koradb.sql.ast.ColumnDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Logical node representing a relational join between two sub-plans.
 */
public final class LogicalJoin implements LogicalPlanNode {

    private final LogicalPlanNode left;
    private final LogicalPlanNode right;
    private final Expression onCondition;
    private final JoinType joinType;
    private final Schema schema;

    public LogicalJoin(LogicalPlanNode left, LogicalPlanNode right, Expression onCondition, JoinType joinType) {
        this.left = Objects.requireNonNull(left);
        this.right = Objects.requireNonNull(right);
        this.onCondition = onCondition;
        this.joinType = Objects.requireNonNull(joinType);
        this.schema = computeJoinedSchema(left.getSchema(), right.getSchema());
    }

    public LogicalPlanNode left() {
        return left;
    }

    public LogicalPlanNode right() {
        return right;
    }

    public Expression onCondition() {
        return onCondition;
    }

    public JoinType joinType() {
        return joinType;
    }

    /** Same naming as the join operators: a right column whose name is taken gets a "_2" suffix. */
    private static Schema computeJoinedSchema(Schema leftSchema, Schema rightSchema) {
        return dev.koradb.execution.operator.JoinSupport.mergeSchemas(leftSchema, rightSchema);
    }

    @Override
    public Schema getSchema() {
        return schema;
    }

    @Override
    public List<LogicalPlanNode> getChildren() {
        return List.of(left, right);
    }

    @Override
    public LogicalPlanNode withChildren(List<LogicalPlanNode> newChildren) {
        if (newChildren.size() != 2) {
            throw new IllegalArgumentException("LogicalJoin must have exactly 2 children, got: " + newChildren.size());
        }
        return new LogicalJoin(newChildren.get(0), newChildren.get(1), onCondition, joinType);
    }

    @Override
    public void explain(StringBuilder sb, String prefix, boolean isTail) {
        sb.append(prefix).append(isTail ? "└── " : "├── ")
                .append("LogicalJoin [type=").append(joinType)
                .append(", on=").append(onCondition).append("]\n");
        left.explain(sb, prefix + (isTail ? "    " : "│   "), false);
        right.explain(sb, prefix + (isTail ? "    " : "│   "), true);
    }

    @Override
    public String toString() {
        return "LogicalJoin(" + joinType + ", on=" + onCondition + ")";
    }
}
