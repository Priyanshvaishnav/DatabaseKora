package dev.koradb.planner.explain;

import dev.koradb.KoraDB;
import dev.koradb.execution.QueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EXPLAIN")
class ExplainTest {

    private KoraDB db;

    @BeforeEach
    void setUp() {
        db = new KoraDB();
        db.execute("CREATE DATABASE explain_db");
        db.execute("USE explain_db");
        db.execute("CREATE TABLE users (id INT, name VARCHAR, age INT)");
        db.execute("INSERT INTO users VALUES (1, 'Alice', 25)");
        db.execute("INSERT INTO users VALUES (2, 'Bob', 30)");
        db.execute("INSERT INTO users VALUES (3, 'Charlie', 35)");
    }

    @Test
    @DisplayName("EXPLAIN produces logical and physical plan trees")
    void explainPlan() {
        QueryResult result = db.execute("EXPLAIN SELECT id, name FROM users WHERE age > 25");
        assertThat(result.columns()).containsExactly("QUERY PLAN");

        String planText = result.toDisplayString();
        assertThat(planText).contains("=== LOGICAL PLAN ===");
        assertThat(planText).contains("=== OPTIMIZED LOGICAL PLAN ===");
        assertThat(planText).contains("=== PHYSICAL PLAN ===");
        assertThat(planText).contains("LogicalScan [table=users");
    }

    @Test
    @DisplayName("EXPLAIN ANALYZE executes query and outputs execution profile")
    void explainAnalyze() {
        QueryResult result = db.execute("EXPLAIN ANALYZE SELECT id, name FROM users WHERE age > 25");
        assertThat(result.columns()).containsExactly("QUERY PLAN");

        String planText = result.toDisplayString();
        assertThat(planText).contains("=== LOGICAL PLAN ===");
        assertThat(planText).contains("=== OPTIMIZED LOGICAL PLAN ===");
        assertThat(planText).contains("=== PHYSICAL PLAN ===");
        assertThat(planText).contains("=== EXECUTION PROFILE ===");
        assertThat(planText).contains("Actual rows: 2");
        assertThat(planText).contains("Execution time:");
    }
}
