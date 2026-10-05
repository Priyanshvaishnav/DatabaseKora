package dev.koradb.planner;

import dev.koradb.catalog.Catalog;
import dev.koradb.planner.logical.LogicalPlanBuilder;
import dev.koradb.planner.logical.LogicalPlanNode;
import dev.koradb.planner.optimizer.RuleBasedOptimizer;
import dev.koradb.planner.physical.PhysicalPlan;
import dev.koradb.planner.physical.PhysicalPlanner;
import dev.koradb.sql.ast.SelectStmt;

import java.util.Objects;

/**
 * Top-level query compilation facade orchestrating the two-phase optimization pipeline:
 * <ol>
 *   <li><b>AST → Initial Logical Plan</b> via {@link LogicalPlanBuilder}</li>
 *   <li><b>Logical Plan Rewriting</b> via {@link RuleBasedOptimizer} (RBO)</li>
 *   <li><b>Cost-Based Physical Operator Selection</b> via {@link PhysicalPlanner} (CBO)</li>
 * </ol>
 */
public final class QueryPlanner {

    private final RuleBasedOptimizer rbo;

    public QueryPlanner() {
        this(new RuleBasedOptimizer());
    }

    public QueryPlanner(RuleBasedOptimizer rbo) {
        this.rbo = Objects.requireNonNull(rbo);
    }

    /**
     * Fully compiles a SELECT statement through logical optimization and physical planning.
     */
    public CompilationResult plan(SelectStmt stmt, Catalog catalog) {
        Objects.requireNonNull(stmt, "SelectStmt must not be null");
        Objects.requireNonNull(catalog, "Catalog must not be null");

        LogicalPlanNode initialPlan = LogicalPlanBuilder.build(stmt, catalog);
        LogicalPlanNode optimizedPlan = rbo.optimize(initialPlan, catalog);
        PhysicalPlanner physicalPlanner = new PhysicalPlanner(catalog);
        PhysicalPlan physicalPlan = physicalPlanner.plan(optimizedPlan);

        return new CompilationResult(initialPlan, optimizedPlan, physicalPlan);
    }

    /**
     * Bundle containing the planning artifacts across all optimization stages.
     */
    public record CompilationResult(
            LogicalPlanNode initialLogicalPlan,
            LogicalPlanNode optimizedLogicalPlan,
            PhysicalPlan physicalPlan
    ) {}
}
