package dev.koradb.planner.optimizer;

import dev.koradb.catalog.Catalog;
import dev.koradb.planner.logical.LogicalPlanNode;

import java.util.List;
import java.util.Objects;

/**
 * Executes a deterministic pipeline of rule-based logical plan optimizations.
 *
 * <p>The rules apply algebraic transformations to rewrite the plan into an
 * equivalent, globally more efficient logical tree before physical cost-based planning.
 */
public final class RuleBasedOptimizer {

    private final List<Rule> rules;

    public RuleBasedOptimizer() {
        this(List.of(
                new ConstantFoldingRule(),
                new PredicatePushdownRule(),
                new ProjectionPruningRule(),
                new LimitPushdownRule()
        ));
    }

    public RuleBasedOptimizer(List<Rule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules));
    }

    /**
     * Applies all optimization rules sequentially to the logical plan root.
     *
     * @param root    the initial logical plan
     * @param catalog the catalog for schema and table lookups
     * @return the rewritten, optimized logical plan
     */
    public LogicalPlanNode optimize(LogicalPlanNode root, Catalog catalog) {
        LogicalPlanNode current = root;
        for (Rule rule : rules) {
            current = rule.apply(current, catalog);
        }
        return current;
    }
}
