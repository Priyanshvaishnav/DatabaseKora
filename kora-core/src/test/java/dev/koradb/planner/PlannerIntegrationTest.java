package dev.koradb.planner;

import dev.koradb.KoraDB;
import dev.koradb.execution.QueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlannerIntegrationTest")
class PlannerIntegrationTest {

    @Test
    @DisplayName("executes complex optimized query with join, pushdown, sort, limit")
    void complexQueryOptimization() {
        try (KoraDB db = new KoraDB()) {
            db.execute("CREATE DATABASE shop");
            db.execute("USE shop");
            db.execute("CREATE TABLE customers (id INT, name VARCHAR, rating INT)");
            db.execute("CREATE TABLE orders (id INT, customer_id INT, amount DOUBLE)");

            db.execute("INSERT INTO customers VALUES (1, 'Alice', 5)");
            db.execute("INSERT INTO customers VALUES (2, 'Bob', 3)");
            db.execute("INSERT INTO customers VALUES (3, 'Charlie', 4)");
            db.execute("INSERT INTO customers VALUES (4, 'Dave', 2)");

            db.execute("INSERT INTO orders VALUES (101, 1, 250.0)");
            db.execute("INSERT INTO orders VALUES (102, 1, 150.0)");
            db.execute("INSERT INTO orders VALUES (103, 2, 50.0)");
            db.execute("INSERT INTO orders VALUES (104, 3, 300.0)");

            // Query with join, where on both sides, order by, limit
            String sql = "SELECT customers.name, orders.amount " +
                    "FROM customers JOIN orders ON customers.id = orders.customer_id " +
                    "WHERE customers.rating >= 4 AND orders.amount > 100.0 " +
                    "ORDER BY orders.amount DESC LIMIT 2";

            QueryResult result = db.execute(sql);
            assertThat(result.rows().size()).isEqualTo(2);

            // Alice had 250 and 150 (rating 5). Charlie had 300 (rating 4).
            // Ordered by amount DESC: Charlie (300.0), Alice (250.0)
            assertThat(result.rows().get(0).get("name")).isEqualTo("Charlie");
            assertThat(result.rows().get(0).get("amount")).isEqualTo(300.0);
            assertThat(result.rows().get(1).get("name")).isEqualTo("Alice");
            assertThat(result.rows().get(1).get("amount")).isEqualTo(250.0);
        }
    }

    @Test
    @DisplayName("disk-backed optimizer maintains index access and persistence across restarts")
    void diskPersistenceAndIndexAccess(@TempDir Path tempDir) {
        // Step 1: Create DB, populate disk tables and index
        try (KoraDB db = new KoraDB(tempDir)) {
            db.execute("CREATE DATABASE persist_test");
            db.execute("USE persist_test");
            db.execute("CREATE TABLE accounts (id INT, holder VARCHAR, balance DOUBLE)");
            db.execute("CREATE UNIQUE INDEX idx_acc_id ON accounts (id)");

            for (int i = 1; i <= 50; i++) {
                db.execute("INSERT INTO accounts VALUES (" + i + ", 'User" + i + "', " + (i * 100.0) + ")");
            }
        }

        // Step 2: Restart KoraDB from disk directory
        try (KoraDB db = new KoraDB(tempDir)) {
            db.execute("USE persist_test");

            // Point lookup: CBO should use IndexScan
            QueryResult explainPoint = db.execute("EXPLAIN SELECT holder, balance FROM accounts WHERE id = 25");
            assertThat(explainPoint.toDisplayString()).contains("IndexScan");

            QueryResult pointResult = db.execute("SELECT holder, balance FROM accounts WHERE id = 25");
            assertThat(pointResult.rows().size()).isEqualTo(1);
            assertThat(pointResult.rows().get(0).get("holder")).isEqualTo("User25");
            assertThat(pointResult.rows().get(0).get("balance")).isEqualTo(2500.0);

            // Range / Aggregate query
            QueryResult aggResult = db.execute("SELECT COUNT(*) AS total, SUM(balance) AS sum_bal FROM accounts WHERE id <= 10");
            assertThat(aggResult.rows().size()).isEqualTo(1);
            assertThat(aggResult.rows().get(0).get("total")).isEqualTo(10L);
            assertThat(aggResult.rows().get(0).get("sum_bal")).isEqualTo(5500.0);
        }
    }
}
