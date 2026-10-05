package dev.koradb.planner.logical;

import dev.koradb.catalog.Schema;
import dev.koradb.sql.ast.BinaryExpr;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Logical leaf node representing a scan over a table.
 *
 * <p>May hold projected columns (if pruned by projection pushdown) and
 * pushed predicates (if pushed down by predicate pushdown).
 */
public final class LogicalScan implements LogicalPlanNode {

    private final String tableName;
    private final Schema schema;
    private final Optional<String> alias;
    private final List<String> projectedColumns;
    private final List<BinaryExpr> pushedPredicates;

    public LogicalScan(String tableName, Schema schema) {
        this(tableName, schema, Optional.empty(), List.of(), List.of());
    }

    public LogicalScan(String tableName, Schema schema, Optional<String> alias,
                       List<String> projectedColumns, List<BinaryExpr> pushedPredicates) {
        this.tableName = Objects.requireNonNull(tableName);
        this.schema = Objects.requireNonNull(schema);
        this.alias = alias != null ? alias : Optional.empty();
        this.projectedColumns = projectedColumns != null ? List.copyOf(projectedColumns) : List.of();
        this.pushedPredicates = pushedPredicates != null ? List.copyOf(pushedPredicates) : List.of();
    }

    public String tableName() {
        return tableName;
    }

    public Optional<String> alias() {
        return alias;
    }

    public List<String> projectedColumns() {
        return projectedColumns;
    }

    public List<BinaryExpr> pushedPredicates() {
        return pushedPredicates;
    }

    public LogicalScan withProjectedColumns(List<String> columns) {
        return new LogicalScan(tableName, schema, alias, columns, pushedPredicates);
    }

    public LogicalScan withPushedPredicates(List<BinaryExpr> predicates) {
        return new LogicalScan(tableName, schema, alias, projectedColumns, predicates);
    }

    @Override
    public Schema getSchema() {
        return schema;
    }

    @Override
    public List<LogicalPlanNode> getChildren() {
        return Collections.emptyList();
    }

    @Override
    public LogicalPlanNode withChildren(List<LogicalPlanNode> newChildren) {
        return this;
    }

    @Override
    public void explain(StringBuilder sb, String prefix, boolean isTail) {
        sb.append(prefix).append(isTail ? "└── " : "├── ").append("LogicalScan [table=").append(tableName);
        if (alias.isPresent()) {
            sb.append(" AS ").append(alias.get());
        }
        if (!projectedColumns.isEmpty()) {
            sb.append(", columns=").append(projectedColumns);
        }
        if (!pushedPredicates.isEmpty()) {
            sb.append(", pushedPredicates=").append(pushedPredicates);
        }
        sb.append("]\n");
    }

    @Override
    public String toString() {
        return "LogicalScan(" + tableName + ")";
    }
}
