package dev.koradb.storage;

import dev.koradb.KoraDB;
import dev.koradb.execution.QueryResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Milestone 2 integration test — verifies that data written to disk
 * survives a simulated JVM restart.
 *
 * <h2>How the restart is simulated</h2>
 * <p>We create a {@code KoraDB(dataDir)} instance, write data, then
 * close it and create a <em>new</em> {@code KoraDB(dataDir)} instance
 * pointing at the same directory. If data is correctly persisted,
 * the new instance must see all the rows we wrote.
 *
 * <p>JUnit's {@code @TempDir} provides a fresh directory per test that
 * is automatically cleaned up after the test completes.
 */
@DisplayName("Disk Storage — Restart Survival")
class DiskStorageIntegrationTest {

    @TempDir
    Path dataDir;

    // =========================================================================
    // Core restart test
    // =========================================================================

    @Test
    @DisplayName("rows written in session 1 are visible in session 2 (restart survival)")
    void datasurvivesRestart() {
        // ── Session 1: write ──────────────────────────────────────────────────
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE shop");
            db.execute("USE shop");
            db.execute("CREATE TABLE products (id INT, name VARCHAR, price DOUBLE, active BOOLEAN)");
            db.execute("INSERT INTO products VALUES (1, 'Laptop', 999.99, true)");
            db.execute("INSERT INTO products VALUES (2, 'Mouse', 29.99, true)");
            db.execute("INSERT INTO products VALUES (3, 'Monitor', 399.99, false)");
        } // ← KoraDB closed here; all file handles released

        // ── Session 2: read ───────────────────────────────────────────────────
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("USE shop");
            QueryResult result = db.execute("SELECT * FROM products");

            assertThat(result.rows()).hasSize(3);
            assertThat(result.rows().get(0).get("name")).isEqualTo("Laptop");
            assertThat(result.rows().get(1).get("name")).isEqualTo("Mouse");
            assertThat(result.rows().get(2).get("name")).isEqualTo("Monitor");
        }
    }

    @Test
    @DisplayName("WHERE filter works correctly after restart")
    void whereFilterAfterRestart() {
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE inv");
            db.execute("USE inv");
            db.execute("CREATE TABLE items (id INT, name VARCHAR, qty INT, available BOOLEAN)");
            db.execute("INSERT INTO items VALUES (1, 'Widget', 100, true)");
            db.execute("INSERT INTO items VALUES (2, 'Gadget', 0, false)");
            db.execute("INSERT INTO items VALUES (3, 'Doohickey', 50, true)");
        }

        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("USE inv");
            QueryResult result = db.execute("SELECT name FROM items WHERE available = true");
            assertThat(result.rows()).hasSize(2);
            List<String> names = result.rows().stream()
                    .map(r -> (String) r.get("name"))
                    .toList();
            assertThat(names).containsExactlyInAnyOrder("Widget", "Doohickey");
        }
    }

    @Test
    @DisplayName("DELETE is persisted — deleted rows absent after restart")
    void deletePersistedAfterRestart() {
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE sales");
            db.execute("USE sales");
            db.execute("CREATE TABLE orders (id INT, total DOUBLE, paid BOOLEAN)");
            db.execute("INSERT INTO orders VALUES (1, 50.0, true)");
            db.execute("INSERT INTO orders VALUES (2, 75.0, false)");
            db.execute("INSERT INTO orders VALUES (3, 120.0, true)");
            db.execute("DELETE FROM orders WHERE paid = false");
        }

        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("USE sales");
            QueryResult result = db.execute("SELECT * FROM orders");
            // Only 2 rows survive — id=2 was deleted
            assertThat(result.rows()).hasSize(2);
            result.rows().forEach(row ->
                    assertThat(row.get("paid")).isEqualTo(true));
        }
    }

    @Test
    @DisplayName("UPDATE is persisted — updated values visible after restart")
    void updatePersistedAfterRestart() {
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE hr");
            db.execute("USE hr");
            db.execute("CREATE TABLE employees (id INT, name VARCHAR, salary DOUBLE)");
            db.execute("INSERT INTO employees VALUES (1, 'Alice', 80000.0)");
            db.execute("INSERT INTO employees VALUES (2, 'Bob', 60000.0)");
            db.execute("UPDATE employees SET salary = 90000.0 WHERE id = 1");
        }

        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("USE hr");
            QueryResult result = db.execute("SELECT salary FROM employees WHERE id = 1");
            assertThat(result.rows()).hasSize(1);
            assertThat((Double) result.rows().get(0).get("salary"))
                    .isCloseTo(90000.0, within(0.01));
        }
    }

    @Test
    @DisplayName("multiple tables in same database survive restart independently")
    void multipleTablesAfterRestart() {
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE multi");
            db.execute("USE multi");
            db.execute("CREATE TABLE t1 (id INT, val VARCHAR)");
            db.execute("CREATE TABLE t2 (id INT, score DOUBLE)");
            db.execute("INSERT INTO t1 VALUES (1, 'alpha')");
            db.execute("INSERT INTO t1 VALUES (2, 'beta')");
            db.execute("INSERT INTO t2 VALUES (10, 9.5)");
        }

        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("USE multi");

            QueryResult r1 = db.execute("SELECT * FROM t1");
            QueryResult r2 = db.execute("SELECT * FROM t2");

            assertThat(r1.rows()).hasSize(2);
            assertThat(r2.rows()).hasSize(1);
            assertThat((Double) r2.rows().get(0).get("score")).isCloseTo(9.5, within(0.01));
        }
    }

    @Test
    @DisplayName("schema is preserved after restart — column names and types correct")
    void schemaPreservedAfterRestart() {
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE schema_test");
            db.execute("USE schema_test");
            db.execute("CREATE TABLE typed (i INT, b BIGINT, d DOUBLE, v VARCHAR, bl BOOLEAN)");
            db.execute("INSERT INTO typed VALUES (1, 9999999999, 3.14, 'hello', true)");
        }

        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("USE schema_test");
            QueryResult result = db.execute("SELECT * FROM typed");

            assertThat(result.rows()).hasSize(1);
            var row = result.rows().get(0);
            assertThat(row.get("i")).isInstanceOf(Integer.class).isEqualTo(1);
            assertThat(row.get("b")).isInstanceOf(Long.class).isEqualTo(9999999999L);
            assertThat((Double) row.get("d")).isCloseTo(3.14, within(0.001));
            assertThat(row.get("v")).isEqualTo("hello");
            assertThat(row.get("bl")).isEqualTo(true);
        }
    }
}
