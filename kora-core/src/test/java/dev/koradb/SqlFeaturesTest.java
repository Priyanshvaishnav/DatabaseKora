package dev.koradb;

import dev.koradb.catalog.Row;
import dev.koradb.exception.KoraException;
import dev.koradb.execution.QueryResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Expressions, joins, grouping, subqueries, constraints and ALTER TABLE on a disk database. */
class SqlFeaturesTest {

    private Path dir;
    private KoraDB db;

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("kora-sql-features");
        db = new KoraDB(dir);
        db.execute("CREATE DATABASE s");
        db.execute("USE s");
        db.execute("CREATE TABLE emp (id INT PRIMARY KEY, name VARCHAR NOT NULL, dept INT, salary DOUBLE, city VARCHAR)");
        db.execute("CREATE TABLE dept (did INT, dname VARCHAR)");
        db.execute("INSERT INTO emp VALUES (1, 'Asha', 10, 5000.0, 'Pune'), (2, 'Ravi', 10, 4000.0, 'Delhi'), "
                + "(3, 'Meera', 20, 7000.0, NULL), (4, 'Arjun', NULL, 3000.0, 'Pune'), (5, 'Zoe', 30, NULL, 'Goa')");
        db.execute("INSERT INTO dept (dname, did) VALUES ('eng', 10), ('ops', 20), ('hr', 40)");
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private List<List<Object>> rows(String sql) {
        QueryResult r = db.execute(sql);
        List<List<Object>> out = new ArrayList<>();
        for (Row row : r.rows()) {
            List<Object> vals = new ArrayList<>();
            for (String c : r.columns()) vals.add(row.get(c));
            out.add(vals);
        }
        return out;
    }

    private List<Object> col(String sql) {
        return rows(sql).stream().map(r -> r.get(0)).toList();
    }

    @Test
    @DisplayName("OR, NOT, <>, IN, BETWEEN, LIKE, IS NULL")
    void predicates() {
        assertThat(col("SELECT id FROM emp WHERE id = 1 OR city = 'Goa' ORDER BY id")).containsExactly(1, 5);
        assertThat(col("SELECT id FROM emp WHERE NOT (dept = 10) ORDER BY id")).containsExactly(3, 5);   // NULL dept: NOT UNKNOWN
        assertThat(col("SELECT id FROM emp WHERE id <> 1 AND id != 2 ORDER BY id")).containsExactly(3, 4, 5);
        assertThat(col("SELECT id FROM emp WHERE dept IN (20, 30) ORDER BY id")).containsExactly(3, 5);
        assertThat(col("SELECT id FROM emp WHERE dept NOT IN (10, NULL)")).isEmpty();
        assertThat(col("SELECT id FROM emp WHERE salary BETWEEN 4000 AND 5000 ORDER BY id")).containsExactly(1, 2);
        assertThat(col("SELECT id FROM emp WHERE name LIKE 'A%' ORDER BY id")).containsExactly(1, 4);
        assertThat(col("SELECT id FROM emp WHERE name LIKE '_e%' ORDER BY id")).containsExactly(3);
        assertThat(col("SELECT id FROM emp WHERE city IS NULL OR salary IS NULL ORDER BY id")).containsExactly(3, 5);
    }

    @Test
    @DisplayName("Arithmetic in SELECT, WHERE and UPDATE SET; SELECT without FROM")
    void arithmetic() {
        assertThat(rows("SELECT 1 + 2 * 3, 7 / 2, 7 % 3, -(4)")).containsExactly(List.of(7, 3, 1, -4));
        assertThat(col("SELECT salary * 2 AS double_pay FROM emp WHERE id = 1")).containsExactly(10000.0);
        assertThat(col("SELECT id FROM emp WHERE salary / 1000 > 4.5 ORDER BY id")).containsExactly(1, 3);
        db.execute("UPDATE emp SET salary = salary + 500, dept = dept * 10 WHERE id = 2");
        assertThat(rows("SELECT salary, dept FROM emp WHERE id = 2")).containsExactly(List.of(4500.0, 100));
        assertThatThrownBy(() -> db.execute("SELECT 1 / 0")).isInstanceOf(KoraException.class).hasMessageContaining("Division by zero");
    }

    @Test
    @DisplayName("Aliases, LEFT JOIN and three-way joins")
    void joins() {
        assertThat(rows("SELECT e.name, d.dname FROM emp e JOIN dept d ON e.dept = d.did WHERE d.dname = 'eng' ORDER BY e.id"))
                .containsExactly(List.of("Asha", "eng"), List.of("Ravi", "eng"));
        assertThat(rows("SELECT e.id, d.dname FROM emp e LEFT JOIN dept d ON e.dept = d.did ORDER BY e.id"))
                .containsExactly(List.of(1, "eng"), List.of(2, "eng"), List.of(3, "ops"), Arrays.asList(4, null), Arrays.asList(5, null));
        // a right-side condition in WHERE is applied after the LEFT JOIN
        assertThat(col("SELECT e.id FROM emp e LEFT JOIN dept d ON e.dept = d.did WHERE d.dname IS NULL ORDER BY e.id"))
                .containsExactly(4, 5);
        db.execute("CREATE TABLE proj (pid INT, dept INT)");
        db.execute("INSERT INTO proj VALUES (100, 10), (200, 20), (300, 20)");
        assertThat(rows("SELECT e.id, p.pid FROM emp e JOIN dept d ON e.dept = d.did JOIN proj p ON p.dept = d.did WHERE e.id = 3"))
                .containsExactlyInAnyOrder(List.of(3, 200), List.of(3, 300));
    }

    @Test
    @DisplayName("DISTINCT, GROUP BY several columns, HAVING")
    void grouping() {
        assertThat(col("SELECT DISTINCT dept FROM emp WHERE dept IS NOT NULL ORDER BY dept")).containsExactly(10, 20, 30);
        assertThat(rows("SELECT dept, city, COUNT(*) FROM emp WHERE dept = 10 GROUP BY dept, city ORDER BY city"))
                .containsExactly(List.of(10, "Delhi", 1L), List.of(10, "Pune", 1L));
        assertThat(rows("SELECT city, COUNT(*) FROM emp GROUP BY city HAVING COUNT(*) > 1")).containsExactly(List.of("Pune", 2L));
        assertThat(col("SELECT dept FROM emp GROUP BY dept HAVING MAX(salary) > 6000")).containsExactly(20);
        assertThatThrownBy(() -> db.execute("SELECT name, COUNT(*) FROM emp GROUP BY dept"))
                .isInstanceOf(KoraException.class).hasMessageContaining("GROUP BY");
    }

    @Test
    @DisplayName("Scalar, IN and EXISTS subqueries")
    void subqueries() {
        assertThat(col("SELECT name FROM emp WHERE salary = (SELECT MAX(salary) FROM emp)")).containsExactly("Meera");
        assertThat(col("SELECT id FROM emp WHERE dept IN (SELECT did FROM dept WHERE dname <> 'eng')")).containsExactly(3);
        assertThat(col("SELECT COUNT(*) FROM emp WHERE NOT EXISTS (SELECT did FROM dept WHERE did = 99)")).containsExactly(5L);
        assertThat(col("SELECT COUNT(*) FROM emp WHERE dept NOT IN (SELECT did FROM dept WHERE did = 99)")).containsExactly(5L);
        assertThatThrownBy(() -> db.execute("SELECT id FROM emp WHERE id = (SELECT did FROM dept)"))
                .isInstanceOf(KoraException.class).hasMessageContaining("at most one");
    }

    @Test
    @DisplayName("INSERT with column list and several rows; constraints")
    void insertAndConstraints() {
        db.execute("INSERT INTO emp (name, id) VALUES ('Nia', 6), ('Om', 7)");
        assertThat(rows("SELECT id, name, dept FROM emp WHERE id >= 6 ORDER BY id"))
                .containsExactly(Arrays.asList(6, "Nia", null), Arrays.asList(7, "Om", null));
        assertThatThrownBy(() -> db.execute("INSERT INTO emp (id, name) VALUES (1, 'dup')")).isInstanceOf(KoraException.class);
        assertThatThrownBy(() -> db.execute("INSERT INTO emp (id) VALUES (8)")).hasMessageContaining("NOT NULL");
        // a bad row in a multi-row INSERT inserts nothing
        assertThatThrownBy(() -> db.execute("INSERT INTO emp (id, name) VALUES (9, 'ok'), (10, NULL)")).hasMessageContaining("NOT NULL");
        assertThat(col("SELECT COUNT(*) FROM emp WHERE id >= 9")).containsExactly(0L);
    }

    @Test
    @DisplayName("TRUNCATE, IF [NOT] EXISTS, ALTER TABLE ADD COLUMN across a restart")
    void ddl() throws Exception {
        db.execute("CREATE TABLE IF NOT EXISTS dept (x INT)");
        db.execute("DROP TABLE IF EXISTS missing");
        db.execute("ALTER TABLE emp ADD COLUMN bonus INT");
        db.execute("INSERT INTO emp VALUES (6, 'Nia', 10, 100.0, 'Goa', 7)");
        db.execute("UPDATE emp SET bonus = id * 10 WHERE id <= 2");
        assertThat(rows("SELECT id, bonus FROM emp ORDER BY id"))
                .containsExactly(List.of(1, 10), List.of(2, 20), Arrays.asList(3, null), Arrays.asList(4, null), Arrays.asList(5, null), List.of(6, 7));
        db.close();
        db = new KoraDB(dir);
        db.execute("USE s");
        assertThat(rows("SELECT id, name, bonus FROM emp WHERE id IN (1, 3, 6) ORDER BY id"))
                .containsExactly(List.of(1, "Asha", 10), Arrays.asList(3, "Meera", null), List.of(6, "Nia", 7));
        assertThatThrownBy(() -> db.execute("INSERT INTO emp (id) VALUES (8)")).hasMessageContaining("NOT NULL");
        db.execute("TRUNCATE TABLE dept");
        assertThat(col("SELECT COUNT(*) FROM dept")).containsExactly(0L);
    }

    @Test
    @DisplayName("Double-quoted identifiers allow reserved words as names")
    void quotedIdentifiers() {
        db.execute("CREATE TABLE \"order\" (\"select\" INT, \"Total Amount\" DOUBLE)");
        db.execute("INSERT INTO \"order\" VALUES (1, 9.5)");
        assertThat(rows("SELECT \"select\", \"Total Amount\" FROM \"order\" WHERE \"select\" = 1")).containsExactly(List.of(1, 9.5));
    }

    @Test
    @DisplayName("Quoted names with spaces survive a restart; names that would escape the data directory are rejected")
    void quotedNamesPersistAndAreSafe() throws Exception {
        db.execute("CREATE TABLE \"line items\" (\"unit price\" DOUBLE, \"qty:x\" INT NOT NULL)");
        db.execute("CREATE INDEX \"by price\" ON \"line items\" (\"unit price\")");
        db.execute("INSERT INTO \"line items\" VALUES (2.5, 4)");
        db.close();
        db = new KoraDB(dir);
        db.execute("USE s");
        assertThat(rows("SELECT \"unit price\", \"qty:x\" FROM \"line items\" WHERE \"unit price\" = 2.5")).containsExactly(List.of(2.5, 4));
        assertThatThrownBy(() -> db.execute("INSERT INTO \"line items\" (\"unit price\") VALUES (1.0)")).hasMessageContaining("NOT NULL");
        assertThatThrownBy(() -> db.execute("CREATE DATABASE \"../escape\"")).hasMessageContaining("Invalid database name");
        assertThatThrownBy(() -> db.execute("DROP DATABASE \"../..\"")).hasMessageContaining("Invalid database name");
        assertThatThrownBy(() -> db.execute("CREATE TABLE \"t.toast\" (a INT)")).hasMessageContaining("Invalid table name");
        assertThat(Files.exists(dir.getParent().resolve("escape"))).isFalse();
    }
}
