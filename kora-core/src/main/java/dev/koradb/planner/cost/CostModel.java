package dev.koradb.planner.cost;

import dev.koradb.catalog.TableStatistics;
import dev.koradb.sql.ast.BinaryExpr;
import dev.koradb.sql.ast.ColumnRefExpr;
import dev.koradb.sql.ast.Operator;

import java.util.List;

/**
 * Calibrated cost model estimating I/O and CPU requirements for query operations.
 *
 * <p>I/O cost is measured in 4KB page transfers (sequential vs random seek).
 * CPU cost is calibrated in tuple processing and operator evaluation units.
 */
public final class CostModel {

    // --- Calibrated Hardware Coefficients ---
    public static final double PAGE_IO_COST        = 1.0;   // Sequential disk page fetch
    public static final double RANDOM_PAGE_IO_COST = 4.0;   // Random seek disk page fetch (unclustered index RID lookup)
    public static final double CPU_TUPLE_COST      = 0.01;  // Tuple iteration / processing overhead
    public static final double CPU_OPERATOR_COST   = 0.002; // Expression comparison / filter evaluation
    public static final double CPU_HASH_COST       = 0.02;  // Hash table insertion / probe
    public static final double CPU_SORT_COST       = 0.05;  // Comparison overhead in N log2(N) sort

    private CostModel() {}

    /**
     * Estimates selectivity (0.0 to 1.0) of a single binary comparison predicate.
     */
    public static double estimateSelectivity(BinaryExpr pred, TableStatistics stats) {
        if (pred == null) {
            return 1.0;
        }

        String colName = null;
        if (pred.left() instanceof ColumnRefExpr cref) {
            colName = cref.columnName();
        } else if (pred.right() instanceof ColumnRefExpr cref) {
            colName = cref.columnName();
        }

        long ndv = (stats != null && colName != null) ? stats.distinctValues(colName) : 10;
        if (ndv <= 0) ndv = 1;

        if (pred.operator() == Operator.EQ) {
            return 1.0 / ndv;
        } else if (pred.operator() == Operator.NEQ) {
            return Math.max(0.01, 1.0 - (1.0 / ndv));
        } else {
            // Inequalities (<, <=, >, >=)
            return 0.33;
        }
    }

    /**
     * Estimates joint selectivity of a conjunction of independent predicates.
     */
    public static double estimateConjunctionSelectivity(List<BinaryExpr> predicates, TableStatistics stats) {
        if (predicates == null || predicates.isEmpty()) {
            return 1.0;
        }
        double sel = 1.0;
        for (BinaryExpr pred : predicates) {
            sel *= estimateSelectivity(pred, stats);
        }
        return Math.max(0.0001, sel);
    }

    /**
     * Estimates cost of a sequential table scan.
     */
    public static PlanCost estimateSeqScanCost(int pageCount, long rowCount) {
        double cost = pageCount * PAGE_IO_COST + rowCount * CPU_TUPLE_COST;
        return new PlanCost(cost, rowCount, pageCount);
    }

    /**
     * Estimates cost of an index scan (tree traversal + random page fetches for matched tuples).
     */
    public static PlanCost estimateIndexScanCost(int treeHeight, long matchingRows) {
        double cost = treeHeight * PAGE_IO_COST
                + matchingRows * RANDOM_PAGE_IO_COST
                + matchingRows * CPU_TUPLE_COST;
        return new PlanCost(cost, matchingRows, Math.max(1, (int) matchingRows));
    }

    /**
     * Estimates cost of applying a filter operator.
     */
    public static PlanCost estimateFilterCost(PlanCost childCost, double selectivity, int predicateCount) {
        long outRows = Math.max(1, (long) Math.ceil(childCost.estimatedRows() * selectivity));
        double filterCpu = childCost.estimatedRows() * predicateCount * CPU_OPERATOR_COST;
        return new PlanCost(childCost.totalCost() + filterCpu, outRows, childCost.estimatedPages());
    }

    /**
     * Estimates cost of an in-memory hash join.
     */
    public static PlanCost estimateHashJoinCost(PlanCost buildCost, PlanCost probeCost) {
        double ioCost = (buildCost.estimatedPages() + probeCost.estimatedPages()) * PAGE_IO_COST;
        double cpuCost = (buildCost.estimatedRows() + probeCost.estimatedRows()) * CPU_HASH_COST;
        long outRows = Math.max(1, Math.min(buildCost.estimatedRows(), probeCost.estimatedRows()));
        return new PlanCost(buildCost.totalCost() + probeCost.totalCost() + ioCost + cpuCost,
                outRows, buildCost.estimatedPages() + probeCost.estimatedPages());
    }

    /**
     * Estimates cost of a nested loop join.
     */
    public static PlanCost estimateNestedLoopJoinCost(PlanCost outerCost, PlanCost innerCost) {
        double ioCost = outerCost.estimatedPages() * PAGE_IO_COST
                + outerCost.estimatedRows() * innerCost.estimatedPages() * PAGE_IO_COST;
        double cpuCost = outerCost.estimatedRows() * innerCost.estimatedRows() * CPU_OPERATOR_COST;
        long outRows = Math.max(1, (outerCost.estimatedRows() * innerCost.estimatedRows()) / 5);
        return new PlanCost(outerCost.totalCost() + innerCost.totalCost() + ioCost + cpuCost,
                outRows, outerCost.estimatedPages() + innerCost.estimatedPages());
    }

    /**
     * Estimates cost of an in-memory sort operator.
     */
    public static PlanCost estimateSortCost(PlanCost childCost) {
        long n = childCost.estimatedRows();
        double sortCpu = n <= 1 ? 0 : n * (Math.log(n) / Math.log(2)) * CPU_SORT_COST;
        return new PlanCost(childCost.totalCost() + sortCpu, n, childCost.estimatedPages());
    }
}
