package dev.koradb.planner.optimizer;

import dev.koradb.catalog.Catalog;
import dev.koradb.planner.logical.LogicalPlanNode;

/**
 * Interface for relational algebra optimization rules.
 *
 * <p>A rule transforms an input {@link LogicalPlanNode} sub-tree into an equivalent,
 * more efficient or canonicalized {@link LogicalPlanNode} tree.
 */
public interface Rule {

    /**
     * Applies this rule to the given logical plan node and its sub-tree.
     *
     * @param node    the root of the logical sub-tree to transform
     * @param catalog the catalog for schema and table lookups
     * @return the transformed logical plan node
     */
    LogicalPlanNode apply(LogicalPlanNode node, Catalog catalog);
}
