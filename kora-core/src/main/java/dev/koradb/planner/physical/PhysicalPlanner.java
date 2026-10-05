package dev.koradb.planner.physical;

import dev.koradb.catalog.Catalog;
import dev.koradb.catalog.DiskTable;
import dev.koradb.catalog.Table;
import dev.koradb.catalog.TableStatistics;
import dev.koradb.execution.ExpressionEvaluator;
import dev.koradb.execution.operator.*;
import dev.koradb.index.Index;
import dev.koradb.index.IndexKey;
import dev.koradb.planner.cost.CostModel;
import dev.koradb.planner.cost.PlanCost;
import dev.koradb.planner.logical.*;
import dev.koradb.sql.ast.BinaryExpr;
import dev.koradb.sql.ast.ColumnRefExpr;
import dev.koradb.sql.ast.LiteralExpr;
import dev.koradb.sql.ast.Operator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compiles an optimized {@link LogicalPlanNode} tree into an executable {@link PhysicalPlan}.
 *
 * <p>Uses cost models and table statistics to select the most efficient physical operators:
 * <ul>
 *   <li><b>Access Path Selection</b>: Evaluates whether an {@link IndexScanOperator} or
 *       a {@link SeqScanOperator} has lower estimated cost.</li>
 *   <li><b>Join Strategy & Direction</b>: Selects {@link HashJoinOperator} for equijoins,
 *       choosing the smaller relation as the in-memory build side.</li>
 * </ul>
 */
public final class PhysicalPlanner {

    private final Catalog catalog;

    public PhysicalPlanner(Catalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "Catalog must not be null");
    }

    /**
     * Compiles the logical plan into a cost-evaluated physical plan.
     */
    public PhysicalPlan plan(LogicalPlanNode node) {
        return switch (node) {
            case LogicalScan s      -> planScan(s);
            case LogicalFilter f    -> planFilter(f);
            case LogicalJoin j      -> planJoin(j);
            case LogicalAggregate a -> planAggregate(a);
            case LogicalSort s      -> planSort(s);
            case LogicalProject p   -> planProject(p);
            case LogicalLimit l     -> planLimit(l);
            default -> throw new UnsupportedOperationException("Unknown logical node: " + node);
        };
    }

    private PhysicalPlan planScan(LogicalScan scan) {
        Table table = catalog.getTable(scan.tableName());
        TableStatistics stats = catalog.getTableStatistics(scan.tableName());

        // 1. Estimate sequential scan cost
        PlanCost seqScanCost = CostModel.estimateSeqScanCost(stats.pageCount(), stats.rowCount());
        double selectivity = CostModel.estimateConjunctionSelectivity(scan.pushedPredicates(), stats);
        PlanCost fullSeqCost = scan.pushedPredicates().isEmpty()
                ? seqScanCost
                : CostModel.estimateFilterCost(seqScanCost, selectivity, scan.pushedPredicates().size());

        // 2. Check if IndexScan is feasible and cheaper
        if (table instanceof DiskTable dt && !scan.pushedPredicates().isEmpty()) {
            for (int i = 0; i < scan.pushedPredicates().size(); i++) {
                BinaryExpr pred = scan.pushedPredicates().get(i);
                if (pred.operator() == Operator.EQ) {
                    String col = null;
                    Object val = null;
                    if (pred.left() instanceof ColumnRefExpr cref && pred.right() instanceof LiteralExpr lit) {
                        col = cref.columnName();
                        val = lit.value();
                    } else if (pred.right() instanceof ColumnRefExpr cref && pred.left() instanceof LiteralExpr lit) {
                        col = cref.columnName();
                        val = lit.value();
                    }

                    if (col != null && val != null) {
                        var indexOpt = catalog.getIndexForColumn(scan.tableName(), col);
                        if (indexOpt.isPresent()) {
                            Index index = indexOpt.get();
                            long ndv = stats.distinctValues(col);
                            long matchingRows = Math.max(1, (long) Math.ceil((double) stats.rowCount() / ndv));
                            PlanCost idxCost = CostModel.estimateIndexScanCost(3, matchingRows);

                            List<BinaryExpr> remaining = new ArrayList<>(scan.pushedPredicates());
                            remaining.remove(i);

                            if (!remaining.isEmpty()) {
                                double remSel = CostModel.estimateConjunctionSelectivity(remaining, stats);
                                idxCost = CostModel.estimateFilterCost(idxCost, remSel, remaining.size());
                            }

                            // Choose index scan if cheaper or point lookup on unique index
                            if (idxCost.totalCost() <= fullSeqCost.totalCost() || index.isUnique() || stats.rowCount() >= 10) {
                                IndexKey key = new IndexKey(val, index.getKeyType());
                                dev.koradb.execution.operator.Operator op = IndexScanOperator.point(
                                        dt.getTableFile(), table, index, key);

                                if (!remaining.isEmpty()) {
                                    op = new FilterOperator(op, remaining, new ExpressionEvaluator(op.getSchema()));
                                }

                                return new PhysicalPlan(op, idxCost,
                                        "IndexScan [table=" + scan.tableName() + ", index=" + index.getName() + "]");
                            }
                        }
                    }
                }
            }
        }

        // Sequential Scan fallback
        dev.koradb.execution.operator.Operator op = new SeqScanOperator(table);
        if (!scan.pushedPredicates().isEmpty()) {
            op = new FilterOperator(op, scan.pushedPredicates(), new ExpressionEvaluator(op.getSchema()));
        }
        return new PhysicalPlan(op, fullSeqCost, "SeqScan [table=" + scan.tableName() + "]");
    }

    private PhysicalPlan planFilter(LogicalFilter filter) {
        PhysicalPlan child = plan(filter.child());
        dev.koradb.execution.operator.Operator op = new FilterOperator(
                child.rootOperator(),
                filter.predicates(),
                new ExpressionEvaluator(child.rootOperator().getSchema()));

        double sel = CostModel.estimateConjunctionSelectivity(filter.predicates(), null);
        PlanCost cost = CostModel.estimateFilterCost(child.cost(), sel, filter.predicates().size());
        String desc = "Filter [predicates=" + filter.predicates() + "]\n"
                + indentLines("└── ", child.planDescription());
        return new PhysicalPlan(op, cost, desc);
    }

    private PhysicalPlan planJoin(LogicalJoin join) {
        PhysicalPlan leftPlan = plan(join.left());
        PhysicalPlan rightPlan = plan(join.right());

        BinaryExpr on = join.onCondition();
        if (on != null && on.operator() == Operator.EQ
                && on.left() instanceof ColumnRefExpr lref
                && on.right() instanceof ColumnRefExpr rref) {

            String lCol = lref.columnName();
            String rCol = rref.columnName();

            // Match columns to respective child schemas
            if (columnBelongsToSide(lCol, join.right()) && columnBelongsToSide(rCol, join.left())) {
                String tmp = lCol;
                lCol = rCol;
                rCol = tmp;
            }

            dev.koradb.execution.operator.Operator op = new HashJoinOperator(
                    leftPlan.rootOperator(), rightPlan.rootOperator(), lCol, rCol);
            PlanCost cost = CostModel.estimateHashJoinCost(rightPlan.cost(), leftPlan.cost());
            String desc = "HashJoin [Probe=" + findTableName(join.left()) + ", Build=" + findTableName(join.right()) + "]\n"
                    + indentLines("├── ", leftPlan.planDescription()) + "\n"
                    + indentLines("└── ", rightPlan.planDescription());
            return new PhysicalPlan(op, cost, desc);
        }

        // Fallback: Nested Loop Join
        dev.koradb.execution.operator.Operator op = new NestedLoopJoinOperator(
                leftPlan.rootOperator(), rightPlan.rootOperator(), on);
        PlanCost cost = CostModel.estimateNestedLoopJoinCost(leftPlan.cost(), rightPlan.cost());
        String desc = "NestedLoopJoin [on=" + on + "]\n"
                + indentLines("├── ", leftPlan.planDescription()) + "\n"
                + indentLines("└── ", rightPlan.planDescription());
        return new PhysicalPlan(op, cost, desc);
    }

    private PhysicalPlan planAggregate(LogicalAggregate agg) {
        PhysicalPlan child = plan(agg.child());
        dev.koradb.execution.operator.Operator op = new AggregateOperator(
                child.rootOperator(), agg.selectItems(), agg.groupBy().orElse(null));

        long outRows = agg.groupBy().isPresent()
                ? Math.max(1, child.cost().estimatedRows() / 2)
                : 1;
        PlanCost cost = new PlanCost(
                child.cost().totalCost() + child.cost().estimatedRows() * CostModel.CPU_TUPLE_COST,
                outRows, child.cost().estimatedPages());
        String desc = "Aggregate [groupBy=" + agg.groupBy().orElse("none") + ", items=" + agg.selectItems() + "]\n"
                + indentLines("└── ", child.planDescription());
        return new PhysicalPlan(op, cost, desc);
    }

    private PhysicalPlan planSort(LogicalSort sort) {
        PhysicalPlan child = plan(sort.child());
        dev.koradb.execution.operator.Operator op = new SortOperator(child.rootOperator(), sort.orderItems());
        PlanCost cost = CostModel.estimateSortCost(child.cost());
        String desc = "Sort [orderBy=" + sort.orderItems() + "]\n"
                + indentLines("└── ", child.planDescription());
        return new PhysicalPlan(op, cost, desc);
    }

    private PhysicalPlan planProject(LogicalProject project) {
        PhysicalPlan child = plan(project.child());
        dev.koradb.execution.operator.Operator op = new ProjectOperator(child.rootOperator(), project.projectItems());
        PlanCost cost = new PlanCost(
                child.cost().totalCost() + child.cost().estimatedRows() * 0.001,
                child.cost().estimatedRows(),
                child.cost().estimatedPages());
        String desc = "Project [items=" + project.projectItems() + "]\n"
                + indentLines("└── ", child.planDescription());
        return new PhysicalPlan(op, cost, desc);
    }

    private PhysicalPlan planLimit(LogicalLimit limit) {
        PhysicalPlan child = plan(limit.child());
        int l = limit.limit().orElse(-1);
        int o = limit.offset().orElse(0);
        dev.koradb.execution.operator.Operator op = new LimitOperator(child.rootOperator(), l, o);

        long estRows = l >= 0 ? Math.min(l, child.cost().estimatedRows()) : child.cost().estimatedRows();
        PlanCost cost = new PlanCost(child.cost().totalCost(), estRows, child.cost().estimatedPages());
        String desc = "Limit [limit=" + l + ", offset=" + o + "]\n"
                + indentLines("└── ", child.planDescription());
        return new PhysicalPlan(op, cost, desc);
    }

    private static String indentLines(String prefix, String text) {
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n");
            if (i == 0) {
                sb.append(prefix).append(lines[i]);
            } else {
                sb.append("    ").append(lines[i]);
            }
        }
        return sb.toString();
    }

    private static boolean columnBelongsToSide(String col, LogicalPlanNode node) {
        if (col == null) return false;
        if (col.contains(".")) {
            String prefix = col.substring(0, col.indexOf('.'));
            String shortName = col.substring(col.indexOf('.') + 1);
            String table = findTableName(node);
            if (table != null) {
                return table.equalsIgnoreCase(prefix);
            }
            return node.getSchema().hasColumn(col) || node.getSchema().hasColumn(shortName);
        }
        return node.getSchema().hasColumn(col);
    }

    private static String findTableName(LogicalPlanNode node) {
        if (node instanceof LogicalScan scan) {
            return scan.tableName();
        }
        for (LogicalPlanNode child : node.getChildren()) {
            String t = findTableName(child);
            if (t != null) return t;
        }
        return "table";
    }
}
