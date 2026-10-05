package dev.koradb.execution.operator;

import dev.koradb.catalog.Catalog;
import dev.koradb.planner.QueryPlanner;
import dev.koradb.sql.ast.SelectStmt;

/**
 * Compiles a {@link SelectStmt} AST node into an executable {@link Operator} tree.
 * Delegates to {@link QueryPlanner} for full logical and physical optimization.
 */
public final class PhysicalPlanBuilder {

    private static final QueryPlanner PLANNER = new QueryPlanner();

    private PhysicalPlanBuilder() {}

    /**
     * Builds the optimized operator tree pipeline for a given SELECT statement.
     */
    public static Operator build(SelectStmt stmt, Catalog catalog) {
        return PLANNER.plan(stmt, catalog).physicalPlan().rootOperator();
    }
}
