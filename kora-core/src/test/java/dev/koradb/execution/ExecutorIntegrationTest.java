package dev.koradb.execution;

import dev.koradb.KoraDB;
import dev.koradb.exception.ExecutionException;
import dev.koradb.exception.SchemaException;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end integration tests for the full SQL execution pipeline.
 *
 * <p>Every test goes through: SQL string → Lexer → Parser → Executor → Catalog.
 * No mocking. This is the closest we get to a real user session in M1.
 */
@DisplayName("SQL Execution Integration")
class ExecutorIntegrationTest {

    private KoraDB db;

    @BeforeEach
    void setUp() {
        db = new KoraDB();
        // Set up a standard database and users table used by most tests
        db.execute("CREATE DATABASE testdb");
        db.execute("USE testdb");
        db.execute("CREATE TABLE users (id INT, name VARCHAR, age INT, active BOOLEAN, salary DOUBLE)");
    }

    // =========================================================================
    // Session Flow
    // =========================================================================

    @Nested
    @DisplayName("Session flow")
    class SessionFlow {

        @Test
        @DisplayName("full lifecycle: CREATE DB → USE → CREATE TABLE → INSERT → SELECT → DROP")
        void fullLifecycle() {
            KoraDB fresh = new KoraDB();

            fresh.execute("CREATE DATABASE mydb");
            fresh.execute("USE mydb");
            fresh.execute("CREATE TABLE products (id INT, name VARCHAR)");
            fresh.execute("INSERT INTO products VALUES (1, 'Widget')");

            QueryResult result = fresh.execute("SELECT * FROM products");
            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().get(0).get("name")).isEqualTo("Widget");

            fresh.execute("DROP TABLE products");

            // Table should no longer exist
            assertThatThrownBy(() -> fresh.execute("SELECT * FROM products"))
                    .isInstanceOf(SchemaException.class);
        }

        @Test
        @DisplayName("USE on non-existent database throws SchemaException")
        void useNonExistentDb() {
            assertThatThrownBy(() -> db.execute("USE nonexistent"))
                    .isInstanceOf(SchemaException.class)
                    .hasMessageContaining("nonexistent");
        }
    }

    // =========================================================================
    // INSERT + SELECT
    // =========================================================================

    @Nested
    @DisplayName("INSERT and SELECT")
    class InsertAndSelect {

        @Test
        @DisplayName("SELECT * returns all inserted rows")
        void selectAllRows() {
            db.execute("INSERT INTO users VALUES (1, 'Alice', 30, true, 95000.0)");
            db.execute("INSERT INTO users VALUES (2, 'Bob',   25, false, 60000.0)");

            QueryResult result = db.execute("SELECT * FROM users");
            assertThat(result.rows()).hasSize(2);
        }

        @Test
        @DisplayName("INSERT and SELECT preserves all values correctly")
        void valuesPreserved() {
            db.execute("INSERT INTO users VALUES (1, 'Alice', 30, true, 95000.0)");

            QueryResult result = db.execute("SELECT * FROM users");
            var row = result.rows().get(0);

            assertThat(row.get("id")).isEqualTo(1);
            assertThat(row.get("name")).isEqualTo("Alice");
            assertThat(row.get("age")).isEqualTo(30);
            assertThat(row.get("active")).isEqualTo(true);
            assertThat(row.get("salary")).isEqualTo(95000.0);
        }

        @Test
        @DisplayName("SELECT specific columns projects only those columns")
        void projection() {
            db.execute("INSERT INTO users VALUES (1, 'Alice', 30, true, 95000.0)");

            QueryResult result = db.execute("SELECT name, age FROM users");
            assertThat(result.columns()).containsExactly("name", "age");
            assertThat(result.rows().get(0).values()).containsOnlyKeys("name", "age");
        }

        @Test
        @DisplayName("SELECT from empty table returns empty result")
        void emptyTable() {
            QueryResult result = db.execute("SELECT * FROM users");
            assertThat(result.rows()).isEmpty();
        }

        @Test
        @DisplayName("INSERT wrong value count throws SchemaException")
        void wrongValueCount() {
            assertThatThrownBy(() -> db.execute("INSERT INTO users VALUES (1, 'Alice')"))
                    .isInstanceOf(SchemaException.class)
                    .hasMessageContaining("expected 5");
        }

        @Test
        @DisplayName("SELECT non-existent column throws SchemaException")
        void unknownColumn() {
            assertThatThrownBy(() -> db.execute("SELECT phone FROM users"))
                    .isInstanceOf(SchemaException.class)
                    .hasMessageContaining("phone");
        }
    }

    // =========================================================================
    // WHERE filtering
    // =========================================================================

    @Nested
    @DisplayName("WHERE clause filtering")
    class WhereFiltering {

        @BeforeEach
        void insertData() {
            db.execute("INSERT INTO users VALUES (1, 'Alice', 30, true,  95000.0)");
            db.execute("INSERT INTO users VALUES (2, 'Bob',   25, false, 60000.0)");
            db.execute("INSERT INTO users VALUES (3, 'Carol', 35, true,  120000.0)");
        }

        @Test
        @DisplayName("WHERE age > 28 returns Alice and Carol")
        void greaterThan() {
            QueryResult result = db.execute("SELECT name FROM users WHERE age > 28");
            List<String> names = result.rows().stream()
                    .map(r -> (String) r.get("name")).toList();
            assertThat(names).containsExactlyInAnyOrder("Alice", "Carol");
        }

        @Test
        @DisplayName("WHERE id = 2 returns exactly Bob")
        void equality() {
            QueryResult result = db.execute("SELECT name FROM users WHERE id = 2");
            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().get(0).get("name")).isEqualTo("Bob");
        }

        @Test
        @DisplayName("WHERE age <= 30 returns Alice and Bob")
        void lessOrEqual() {
            QueryResult result = db.execute("SELECT name FROM users WHERE age <= 30");
            List<String> names = result.rows().stream()
                    .map(r -> (String) r.get("name")).toList();
            assertThat(names).containsExactlyInAnyOrder("Alice", "Bob");
        }

        @Test
        @DisplayName("WHERE age != 30 returns Bob and Carol")
        void notEqual() {
            QueryResult result = db.execute("SELECT name FROM users WHERE age != 30");
            assertThat(result.rows()).hasSize(2);
        }

        @Test
        @DisplayName("WHERE with AND — age > 25 AND active = true returns Alice and Carol")
        void andPredicate() {
            QueryResult result = db.execute(
                    "SELECT name FROM users WHERE age > 25 AND active = true");
            List<String> names = result.rows().stream()
                    .map(r -> (String) r.get("name")).toList();
            assertThat(names).containsExactlyInAnyOrder("Alice", "Carol");
        }

        @Test
        @DisplayName("WHERE with no matching rows returns empty result")
        void noMatch() {
            QueryResult result = db.execute("SELECT * FROM users WHERE age > 100");
            assertThat(result.rows()).isEmpty();
        }

        @Test
        @DisplayName("WHERE on boolean column")
        void booleanFilter() {
            QueryResult result = db.execute("SELECT name FROM users WHERE active = true");
            assertThat(result.rows()).hasSize(2);
        }

        @Test
        @DisplayName("WHERE on string column")
        void stringFilter() {
            QueryResult result = db.execute("SELECT * FROM users WHERE name = 'Bob'");
            assertThat(result.rows()).hasSize(1);
        }

        @Test
        @DisplayName("WHERE on double column")
        void doubleFilter() {
            QueryResult result = db.execute("SELECT name FROM users WHERE salary > 90000.0");
            assertThat(result.rows()).hasSize(2);
        }

        @Test
        @DisplayName("WHERE on unknown column throws SchemaException")
        void unknownColumnInWhere() {
            assertThatThrownBy(() -> db.execute("SELECT * FROM users WHERE phone = '123'"))
                    .isInstanceOf(SchemaException.class)
                    .hasMessageContaining("phone");
        }
    }

    // =========================================================================
    // UPDATE
    // =========================================================================

    @Nested
    @DisplayName("UPDATE")
    class Update {

        @BeforeEach
        void insertData() {
            db.execute("INSERT INTO users VALUES (1, 'Alice', 30, true, 95000.0)");
            db.execute("INSERT INTO users VALUES (2, 'Bob',   25, false, 60000.0)");
        }

        @Test
        @DisplayName("UPDATE with WHERE modifies only matching rows")
        void updateWithWhere() {
            QueryResult update = db.execute("UPDATE users SET age = 31 WHERE id = 1");
            assertThat(update.affectedRows()).isEqualTo(1);

            QueryResult select = db.execute("SELECT age FROM users WHERE id = 1");
            assertThat(select.rows().get(0).get("age")).isEqualTo(31);

            // Bob's age unchanged
            QueryResult bob = db.execute("SELECT age FROM users WHERE id = 2");
            assertThat(bob.rows().get(0).get("age")).isEqualTo(25);
        }

        @Test
        @DisplayName("UPDATE multiple columns in one statement")
        void updateMultipleColumns() {
            db.execute("UPDATE users SET age = 26, active = true WHERE id = 2");

            QueryResult result = db.execute("SELECT age, active FROM users WHERE id = 2");
            var row = result.rows().get(0);
            assertThat(row.get("age")).isEqualTo(26);
            assertThat(row.get("active")).isEqualTo(true);
        }

        @Test
        @DisplayName("UPDATE without WHERE updates all rows")
        void updateAllRows() {
            QueryResult result = db.execute("UPDATE users SET active = false");
            assertThat(result.affectedRows()).isEqualTo(2);

            QueryResult check = db.execute("SELECT * FROM users WHERE active = false");
            assertThat(check.rows()).hasSize(2);
        }

        @Test
        @DisplayName("UPDATE with no matching WHERE returns 0 affected rows")
        void updateNoMatch() {
            QueryResult result = db.execute("UPDATE users SET age = 99 WHERE id = 999");
            assertThat(result.affectedRows()).isEqualTo(0);
        }

        @Test
        @DisplayName("UPDATE non-existent column throws SchemaException")
        void updateUnknownColumn() {
            assertThatThrownBy(() -> db.execute("UPDATE users SET phone = '123' WHERE id = 1"))
                    .isInstanceOf(SchemaException.class);
        }
    }

    // =========================================================================
    // DELETE
    // =========================================================================

    @Nested
    @DisplayName("DELETE")
    class Delete {

        @BeforeEach
        void insertData() {
            db.execute("INSERT INTO users VALUES (1, 'Alice', 30, true,  95000.0)");
            db.execute("INSERT INTO users VALUES (2, 'Bob',   25, false, 60000.0)");
            db.execute("INSERT INTO users VALUES (3, 'Carol', 35, true,  120000.0)");
        }

        @Test
        @DisplayName("DELETE with WHERE removes only matching rows")
        void deleteWithWhere() {
            QueryResult del = db.execute("DELETE FROM users WHERE id = 2");
            assertThat(del.affectedRows()).isEqualTo(1);

            QueryResult remaining = db.execute("SELECT * FROM users");
            assertThat(remaining.rows()).hasSize(2);
        }

        @Test
        @DisplayName("DELETE without WHERE removes all rows")
        void deleteAll() {
            QueryResult del = db.execute("DELETE FROM users");
            assertThat(del.affectedRows()).isEqualTo(3);

            QueryResult check = db.execute("SELECT * FROM users");
            assertThat(check.rows()).isEmpty();
        }

        @Test
        @DisplayName("DELETE with non-matching WHERE removes nothing")
        void deleteNoMatch() {
            QueryResult del = db.execute("DELETE FROM users WHERE id = 999");
            assertThat(del.affectedRows()).isEqualTo(0);

            assertThat(db.execute("SELECT * FROM users").rows()).hasSize(3);
        }

        @Test
        @DisplayName("Row count is consistent after multiple deletes")
        void multipleDeletes() {
            db.execute("DELETE FROM users WHERE id = 1");
            db.execute("DELETE FROM users WHERE id = 3");

            QueryResult result = db.execute("SELECT * FROM users");
            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().get(0).get("name")).isEqualTo("Bob");
        }
    }

    // =========================================================================
    // Multiple tables
    // =========================================================================

    @Nested
    @DisplayName("Multiple tables")
    class MultipleTables {

        @Test
        @DisplayName("Two tables in same database are fully independent")
        void tablesAreIsolated() {
            db.execute("CREATE TABLE orders (id INT, user_id INT, total DOUBLE)");
            db.execute("INSERT INTO users  VALUES (1, 'Alice', 30, true, 95000.0)");
            db.execute("INSERT INTO orders VALUES (101, 1, 299.99)");

            assertThat(db.execute("SELECT * FROM users").rows()).hasSize(1);
            assertThat(db.execute("SELECT * FROM orders").rows()).hasSize(1);
        }
    }

    // =========================================================================
    // TIMESTAMP support
    // =========================================================================

    @Nested
    @DisplayName("TIMESTAMP columns")
    class TimestampColumns {

        @Test
        @DisplayName("INSERT and SELECT TIMESTAMP value")
        void insertAndSelectTimestamp() {
            db.execute("CREATE TABLE events (id INT, occurred TIMESTAMP)");
            db.execute("INSERT INTO events VALUES (1, '2024-01-15 10:00:00')");

            QueryResult result = db.execute("SELECT * FROM events");
            assertThat(result.rows()).hasSize(1);
            Object occurred = result.rows().get(0).get("occurred");
            assertThat(occurred).isNotNull();
            // Should be stored as LocalDateTime
            assertThat(occurred.toString()).contains("2024-01-15");
        }

        @Test
        @DisplayName("Invalid TIMESTAMP string throws ExecutionException")
        void invalidTimestamp() {
            db.execute("CREATE TABLE events (id INT, occurred TIMESTAMP)");
            assertThatThrownBy(() ->
                    db.execute("INSERT INTO events VALUES (1, 'not-a-date')"))
                    .isInstanceOf(ExecutionException.class)
                    .hasMessageContaining("TIMESTAMP");
        }
    }

    // =========================================================================
    // Schema validation
    // =========================================================================

    @Nested
    @DisplayName("Schema validation")
    class SchemaValidation {

        @Test
        @DisplayName("CREATE TABLE twice throws SchemaException")
        void duplicateTable() {
            assertThatThrownBy(() -> db.execute("CREATE TABLE users (id INT)"))
                    .isInstanceOf(SchemaException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        @DisplayName("DROP non-existent table throws SchemaException")
        void dropNonExistent() {
            assertThatThrownBy(() -> db.execute("DROP TABLE ghost"))
                    .isInstanceOf(SchemaException.class)
                    .hasMessageContaining("ghost");
        }

        @Test
        @DisplayName("SELECT from non-existent table throws SchemaException")
        void selectNonExistent() {
            assertThatThrownBy(() -> db.execute("SELECT * FROM ghost"))
                    .isInstanceOf(SchemaException.class);
        }
    }
}
