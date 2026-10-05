package dev.koradb.planner.explain;

import dev.koradb.catalog.Catalog;
import dev.koradb.catalog.Row;
import dev.koradb.execution.QueryResult;
import dev.koradb.planner.QueryPlanner;
import dev.koradb.planner.QueryPlanner.CompilationResult;
import dev.koradb.sql.ast.ExplainStmt;
import dev.koradb.sql.ast.SelectStmt;

import java.util.*;

/**
 * Handles execution of {@code EXPLAIN} and {@code EXPLAIN ANALYZE} statements.
 */
public final class ExplainExecutor {

    private final QueryPlanner planner;

    public ExplainExecutor() {
        this(new QueryPlanner());
    }

    public ExplainExecutor(QueryPlanner planner) {
        this.planner = Objects.requireNonNull(planner);
    }

    /**
     * Executes the EXPLAIN statement and formats the output into a single-column table.
     */
    public QueryResult execute(ExplainStmt stmt, Catalog catalog) {
        if (!(stmt.statement() instanceof SelectStmt select)) {
            // Explain for non-SELECT statements
            List<Row> rows = List.of(new Row(Map.of("QUERY PLAN", "EXPLAIN for " + stmt.statement().getClass().getSimpleName())));
            return QueryResult.select(rows, List.of("QUERY PLAN"));
        }

        CompilationResult compilation = planner.plan(select, catalog);
        List<String> lines = new ArrayList<>();

        lines.add("=== LOGICAL PLAN ===");
        for (String line : compilation.initialLogicalPlan().printTree().split("\n")) {
            if (!line.isBlank()) lines.add(line);
        }

        lines.add("");
        lines.add("=== OPTIMIZED LOGICAL PLAN ===");
        for (String line : compilation.optimizedLogicalPlan().printTree().split("\n")) {
            if (!line.isBlank()) lines.add(line);
        }

        lines.add("");
        lines.add("=== PHYSICAL PLAN ===");
        lines.add(String.format("└── %s (%s)",
                compilation.physicalPlan().planDescription(),
                compilation.physicalPlan().cost().format()));

        if (stmt.analyze()) {
            ProfilingOperator profiler = new ProfilingOperator(compilation.physicalPlan().rootOperator());
            profiler.open();
            try {
                while (profiler.next() != null) {
                    // exhaust iterator to measure execution
                }
            } finally {
                profiler.close();
            }

            lines.add("");
            lines.add("=== EXECUTION PROFILE ===");
            lines.add(String.format("Actual rows: %d", profiler.getEmittedRows()));
            lines.add(String.format("Execution time: %.3f ms", profiler.getTotalTimeMillis()));
        }

        List<Row> rows = new ArrayList<>();
        for (String line : lines) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("QUERY PLAN", line);
            rows.add(new Row(values));
        }

        return QueryResult.select(rows, List.of("QUERY PLAN"));
    }
}
