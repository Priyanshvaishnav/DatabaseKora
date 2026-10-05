package dev.koradb.execution;

import dev.koradb.KoraDB;
import dev.koradb.catalog.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Milestone 5: Query Execution & Operator Tree Integration")
class OperatorTreeIntegrationTest {

    @TempDir
    Path dataDir;

    private void seedCompanyDatabase(KoraDB db) {
        db.execute("CREATE DATABASE corp");
        db.execute("USE corp");

        db.execute("CREATE TABLE departments (id INT, name VARCHAR, budget DOUBLE)");
        db.execute("INSERT INTO departments VALUES (10, 'Engineering', 500000.0)");
        db.execute("INSERT INTO departments VALUES (20, 'Research',    300000.0)");
        db.execute("INSERT INTO departments VALUES (30, 'Sales',       200000.0)");

        db.execute("CREATE TABLE employees (id INT, name VARCHAR, dept_id INT, salary DOUBLE, active BOOLEAN)");
        db.execute("INSERT INTO employees VALUES (1, 'Alice',   10, 120000.0, true)");
        db.execute("INSERT INTO employees VALUES (2, 'Bob',     10,  95000.0, true)");
        db.execute("INSERT INTO employees VALUES (3, 'Charlie', 20, 110000.0, false)");
        db.execute("INSERT INTO employees VALUES (4, 'Diana',   30,  85000.0, true)");
        db.execute("INSERT INTO employees VALUES (5, 'Evan',    10, 130000.0, true)");
        db.execute("INSERT INTO employees VALUES (6, 'Fiona',   20, 105000.0, true)");
    }

    @Test
    @DisplayName("ORDER BY sorts results ascending and descending")
    void orderByExecution() {
        try (KoraDB db = new KoraDB(dataDir)) {
            seedCompanyDatabase(db);

            // ORDER BY salary DESC
            QueryResult rDesc = db.execute("SELECT name, salary FROM employees ORDER BY salary DESC");
            assertThat(rDesc.rows()).hasSize(6);
            assertThat(rDesc.rows().get(0).get("name")).isEqualTo("Evan");
            assertThat(rDesc.rows().get(1).get("name")).isEqualTo("Alice");
            assertThat(rDesc.rows().get(5).get("name")).isEqualTo("Diana");

            // ORDER BY id ASC
            QueryResult rAsc = db.execute("SELECT id, name FROM employees ORDER BY id ASC");
            assertThat(rAsc.rows().get(0).get("id")).isEqualTo(1);
            assertThat(rAsc.rows().get(5).get("id")).isEqualTo(6);
        }
    }

    @Test
    @DisplayName("LIMIT and OFFSET paginate query results")
    void limitAndOffsetExecution() {
        try (KoraDB db = new KoraDB(dataDir)) {
            seedCompanyDatabase(db);

            // Top 2 salaries after skipping 1
            QueryResult res = db.execute("SELECT name, salary FROM employees ORDER BY salary DESC LIMIT 2 OFFSET 1");
            assertThat(res.rows()).hasSize(2);
            assertThat(res.rows().get(0).get("name")).isEqualTo("Alice");
            assertThat(res.rows().get(1).get("name")).isEqualTo("Charlie");
        }
    }

    @Test
    @DisplayName("INNER JOIN combines relations on equality predicate")
    void joinExecution() {
        try (KoraDB db = new KoraDB(dataDir)) {
            seedCompanyDatabase(db);

            QueryResult res = db.execute(
                    "SELECT employees.name, departments.name AS dept_name " +
                    "FROM employees " +
                    "JOIN departments ON employees.dept_id = departments.id " +
                    "WHERE employees.active = true " +
                    "ORDER BY employees.name ASC"
            );

            assertThat(res.rows()).hasSize(5); // Charlie is inactive
            assertThat(res.rows().get(0).get("name")).isEqualTo("Alice");
            assertThat(res.rows().get(0).get("dept_name")).isEqualTo("Engineering");
            assertThat(res.rows().get(1).get("name")).isEqualTo("Bob");
            assertThat(res.rows().get(1).get("dept_name")).isEqualTo("Engineering");
            assertThat(res.rows().get(2).get("name")).isEqualTo("Diana");
            assertThat(res.rows().get(2).get("dept_name")).isEqualTo("Sales");
        }
    }

    @Test
    @DisplayName("Aggregate functions COUNT, SUM, AVG, MIN, MAX")
    void aggregateExecution() {
        try (KoraDB db = new KoraDB(dataDir)) {
            seedCompanyDatabase(db);

            QueryResult res = db.execute(
                    "SELECT COUNT(*) AS total_emps, " +
                    "SUM(salary) AS total_payroll, " +
                    "AVG(salary) AS avg_sal, " +
                    "MIN(salary) AS min_sal, " +
                    "MAX(salary) AS max_sal " +
                    "FROM employees WHERE active = true"
            );

            assertThat(res.rows()).hasSize(1);
            Row row = res.rows().get(0);
            assertThat(row.get("total_emps")).isEqualTo(5L);
            assertThat((Double) row.get("total_payroll")).isEqualTo(535000.0);
            assertThat((Double) row.get("avg_sal")).isEqualTo(107000.0);
            assertThat((Double) row.get("min_sal")).isEqualTo(85000.0);
            assertThat((Double) row.get("max_sal")).isEqualTo(130000.0);
        }
    }

    @Test
    @DisplayName("GROUP BY aggregates by bucket column")
    void groupByExecution() {
        try (KoraDB db = new KoraDB(dataDir)) {
            seedCompanyDatabase(db);

            QueryResult res = db.execute(
                    "SELECT dept_id, COUNT(*) AS count, AVG(salary) AS avg_pay " +
                    "FROM employees " +
                    "GROUP BY dept_id " +
                    "ORDER BY dept_id ASC"
            );

            assertThat(res.rows()).hasSize(3);
            assertThat(res.rows().get(0).get("dept_id")).isEqualTo(10);
            assertThat(res.rows().get(0).get("count")).isEqualTo(3L);

            assertThat(res.rows().get(1).get("dept_id")).isEqualTo(20);
            assertThat(res.rows().get(1).get("count")).isEqualTo(2L);

            assertThat(res.rows().get(2).get("dept_id")).isEqualTo(30);
            assertThat(res.rows().get(2).get("count")).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("Operator tree queries survive restart and execute over on-disk pages")
    void operatorTreeRestartPersistence() {
        // Session 1: Create and populate
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE persistent_corp");
            db.execute("USE persistent_corp");
            db.execute("CREATE TABLE metrics (id INT, score DOUBLE)");
            for (int i = 1; i <= 20; i++) {
                db.execute("INSERT INTO metrics VALUES (" + i + ", " + (i * 5.0) + ")");
            }
        }

        // Session 2: Query using complex Volcano pipeline over persisted files
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("USE persistent_corp");

            QueryResult res = db.execute(
                    "SELECT id, score FROM metrics " +
                    "WHERE id > 10 " +
                    "ORDER BY score DESC " +
                    "LIMIT 3"
            );

            assertThat(res.rows()).hasSize(3);
            assertThat(res.rows().get(0).get("id")).isEqualTo(20);
            assertThat(res.rows().get(0).get("score")).isEqualTo(100.0);

            assertThat(res.rows().get(1).get("id")).isEqualTo(19);
            assertThat(res.rows().get(1).get("score")).isEqualTo(95.0);

            assertThat(res.rows().get(2).get("id")).isEqualTo(18);
            assertThat(res.rows().get(2).get("score")).isEqualTo(90.0);
        }
    }
}
