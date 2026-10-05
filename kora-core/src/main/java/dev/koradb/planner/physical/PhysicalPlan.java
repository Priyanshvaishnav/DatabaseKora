package dev.koradb.planner.physical;

import dev.koradb.execution.operator.Operator;
import dev.koradb.planner.cost.PlanCost;

import java.util.Objects;

/**
 * An executable physical operator tree paired with estimated cost and plan metadata.
 *
 * @param rootOperator    the root Volcano iterator operator ready for execution
 * @param cost            the estimated I/O and CPU cost
 * @param planDescription formatted ASCII summary of the physical plan
 */
public record PhysicalPlan(
        Operator rootOperator,
        PlanCost cost,
        String planDescription
) {
    public PhysicalPlan {
        Objects.requireNonNull(rootOperator, "rootOperator must not be null");
        Objects.requireNonNull(cost, "cost must not be null");
    }
}
