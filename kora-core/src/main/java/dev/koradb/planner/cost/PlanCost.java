package dev.koradb.planner.cost;

/**
 * Estimated cost metrics for executing a query plan or operator.
 *
 * @param totalCost      overall scalar cost metric (arbitrary cost units, calibrated against I/O and CPU)
 * @param estimatedRows  estimated number of output tuples emitted
 * @param estimatedPages estimated number of 4KB pages touched
 */
public record PlanCost(double totalCost, long estimatedRows, int estimatedPages) {

    public PlanCost {
        totalCost = Math.max(0.0, totalCost);
        estimatedRows = Math.max(0, estimatedRows);
        estimatedPages = Math.max(1, estimatedPages);
    }

    public static PlanCost of(double cost, long rows) {
        return new PlanCost(cost, rows, 1);
    }

    public String format() {
        return String.format("cost=%.2f rows=%d", totalCost, estimatedRows);
    }
}
