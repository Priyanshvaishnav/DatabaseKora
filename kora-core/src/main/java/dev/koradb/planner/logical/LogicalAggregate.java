package dev.koradb.planner.logical;

import dev.koradb.catalog.Schema;
import dev.koradb.sql.ast.ColumnDef;
import dev.koradb.sql.ast.DataType;
import dev.koradb.sql.ast.SelectItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Logical node representing grouped or scalar aggregations (COUNT, SUM, AVG, MIN, MAX).
 */
public final class LogicalAggregate implements LogicalPlanNode {

    private final LogicalPlanNode child;
    private final List<SelectItem> selectItems;
    private final Optional<String> groupBy;
    private final Schema schema;

    public LogicalAggregate(LogicalPlanNode child, List<SelectItem> selectItems, Optional<String> groupBy) {
        this.child = Objects.requireNonNull(child);
        this.selectItems = List.copyOf(Objects.requireNonNull(selectItems));
        this.groupBy = groupBy != null ? groupBy : Optional.empty();
        this.schema = computeSchema(child.getSchema(), this.selectItems);
    }

    public LogicalPlanNode child() {
        return child;
    }

    public List<SelectItem> selectItems() {
        return selectItems;
    }

    public Optional<String> groupBy() {
        return groupBy;
    }

    private static Schema computeSchema(Schema childSchema, List<SelectItem> items) {
        List<ColumnDef> defs = new ArrayList<>();
        for (SelectItem item : items) {
            if (item.isAggregate()) {
                DataType type = switch (item.aggregateType()) {
                    case COUNT -> DataType.BIGINT;
                    case SUM, AVG -> DataType.DOUBLE;
                    case MIN, MAX -> {
                        String col = item.expression();
                        ColumnDef cd = childSchema.findColumn(col);
                        yield cd != null ? cd.dataType() : DataType.DOUBLE;
                    }
                };
                defs.add(new ColumnDef(item.alias(), type));
            } else {
                ColumnDef cd = childSchema.findColumn(item.expression());
                DataType type = cd != null ? cd.dataType() : DataType.VARCHAR;
                defs.add(new ColumnDef(item.alias(), type));
            }
        }
        return new Schema(defs);
    }

    @Override
    public Schema getSchema() {
        return schema;
    }

    @Override
    public List<LogicalPlanNode> getChildren() {
        return List.of(child);
    }

    @Override
    public LogicalPlanNode withChildren(List<LogicalPlanNode> newChildren) {
        if (newChildren.size() != 1) {
            throw new IllegalArgumentException("LogicalAggregate must have exactly 1 child, got: " + newChildren.size());
        }
        return new LogicalAggregate(newChildren.get(0), selectItems, groupBy);
    }

    @Override
    public void explain(StringBuilder sb, String prefix, boolean isTail) {
        sb.append(prefix).append(isTail ? "└── " : "├── ")
                .append("LogicalAggregate [groupBy=").append(groupBy.orElse("none"))
                .append(", items=").append(selectItems).append("]\n");
        child.explain(sb, prefix + (isTail ? "    " : "│   "), true);
    }

    @Override
    public String toString() {
        return "LogicalAggregate(groupBy=" + groupBy.orElse("none") + ", " + selectItems + ")";
    }
}
