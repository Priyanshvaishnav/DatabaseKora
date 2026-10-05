package dev.koradb.planner.logical;

import dev.koradb.catalog.Schema;
import dev.koradb.sql.ast.ColumnDef;
import dev.koradb.sql.ast.DataType;
import dev.koradb.sql.ast.SelectItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Logical node representing relational projection (selecting and renaming columns).
 */
public final class LogicalProject implements LogicalPlanNode {

    private final LogicalPlanNode child;
    private final List<SelectItem> projectItems;
    private final Schema schema;

    public LogicalProject(LogicalPlanNode child, List<SelectItem> projectItems) {
        this.child = Objects.requireNonNull(child);
        this.projectItems = List.copyOf(Objects.requireNonNull(projectItems));
        this.schema = computeSchema(child.getSchema(), this.projectItems);
    }

    public LogicalPlanNode child() {
        return child;
    }

    public List<SelectItem> projectItems() {
        return projectItems;
    }

    private static Schema computeSchema(Schema childSchema, List<SelectItem> items) {
        List<ColumnDef> defs = new ArrayList<>();
        for (SelectItem item : items) {
            String expr = item.expression();
            DataType type = DataType.VARCHAR; // default
            if (childSchema.hasColumn(expr)) {
                type = childSchema.findColumn(expr).dataType();
            } else if (expr.contains(".")) {
                String shortName = expr.substring(expr.indexOf('.') + 1);
                if (childSchema.hasColumn(shortName)) {
                    type = childSchema.findColumn(shortName).dataType();
                }
            }
            defs.add(new ColumnDef(item.alias(), type));
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
            throw new IllegalArgumentException("LogicalProject must have exactly 1 child, got: " + newChildren.size());
        }
        return new LogicalProject(newChildren.get(0), projectItems);
    }

    @Override
    public void explain(StringBuilder sb, String prefix, boolean isTail) {
        List<String> labels = projectItems.stream().map(SelectItem::toString).toList();
        sb.append(prefix).append(isTail ? "└── " : "├── ").append("LogicalProject [items=").append(labels).append("]\n");
        child.explain(sb, prefix + (isTail ? "    " : "│   "), true);
    }

    @Override
    public String toString() {
        return "LogicalProject(" + projectItems + ")";
    }
}
