package dev.koradb.e2e;

import dev.koradb.KoraDB;
import dev.koradb.execution.QueryResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.koradb.e2e.E2e.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** SQL-Functional rows of the end-to-end report that were FAIL or WARN. */
class SqlFunctionalRegressionTest {

    private Path dir;
    private KoraDB db;

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("kora-e2e-sql");
        db = new KoraDB(dir);
        db.execute("CREATE DATABASE fn_shop");
        db.execute("USE fn_shop");
        db.execute("CREATE TABLE customers (id INT, name VARCHAR, city VARCHAR, age INT, balance DOUBLE)");
        db.execute("CREATE TABLE orders (oid INT, cid INT, amount DOUBLE)");
        db.execute("CREATE TABLE items (oid INT, sku VARCHAR)");
        db.execute("CREATE TABLE scratch (v INT)");
        String[] cities = {"Pune", "Delhi", "Goa", "Mumbai"};
        for (int i = 1; i <= 40; i++) {
            db.execute("INSERT INTO customers VALUES (" + i + ", 'c" + i + "', '" + cities[i % 4] + "', " + (18 + i) + ", " + (i * 10.0) + ")");
            db.execute("INSERT INTO orders VALUES (" + (100 + i) + ", " + (i % 20 + 1) + ", " + (i * 5.0) + ")");
            db.execute("INSERT INTO items VALUES (" + (100 + i) + ", 'sku" + (i % 3) + "')");
        }
    }

    @AfterEach
    void tearDown() {
        db.close();
        E2e.deleteTree(dir);
    }

    @Test
    @DisplayName("F-IDX-07 UNIQUE INDEX: a rejected duplicate is not stored")
    void uniqueIndexRejectedDuplicateNotStored() {
        db.execute("CREATE TABLE u (k INT, v VARCHAR)");
        db.execute("CREATE UNIQUE INDEX u_k ON u (k)");
        db.execute("INSERT INTO u VALUES (1, 'a')");
        assertThatThrownBy(() -> db.execute("INSERT INTO u VALUES (1, 'b')")).isNotNull();
        assertThat(count(db, "SELECT COUNT(*) FROM u")).isEqualTo(1);
        assertThat(count(db, "SELECT COUNT(*) FROM u WHERE k = 1")).isEqualTo(1);
    }

    @Test
    @DisplayName("F-IDX-10 Index on a VARCHAR column with many duplicates returns every match")
    void varcharIndexWithDuplicates() {
        db.execute("CREATE TABLE tags (id INT, tag VARCHAR)");
        StringBuilder sb = new StringBuilder("INSERT INTO tags VALUES ");
        for (int i = 0; i < 600; i++) {
            if (i > 0) sb.append(", ");
            sb.append("(").append(i).append(", 'tag").append(i % 12).append("')");
        }
        db.execute(sb.toString());
        db.execute("CREATE INDEX tags_tag ON tags (tag)");
        for (int t = 0; t < 12; t++) {
            assertThat(count(db, "SELECT COUNT(*) FROM tags WHERE tag = 'tag" + t + "'")).as("tag" + t).isEqualTo(50);
        }
    }

    @Test
    @DisplayName("F-TYP-02/03 Negative integer literals in INSERT and WHERE")
    void negativeLiterals() {
        db.execute("INSERT INTO scratch VALUES (-5)");
        assertThat(count(db, "SELECT COUNT(*) FROM scratch WHERE v = -5")).isEqualTo(1);
        assertThat(count(db, "SELECT COUNT(*) FROM scratch WHERE v < -1")).isEqualTo(1);
    }

    @Test
    @DisplayName("F-TYP-06 NULL literal insert")
    void nullLiteral() {
        db.execute("INSERT INTO customers VALUES (99, NULL, NULL, NULL, NULL)");
        QueryResult r = db.execute("SELECT name, age FROM customers WHERE id = 99");
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).get("name")).isNull();
        assertThat(r.rows().get(0).get("age")).isNull();
    }

    @Test
    @DisplayName("F-TYP-07/08 10 KB and 100 KB strings round-trip (larger than a page)")
    void longStrings() {
        db.execute("CREATE TABLE docs (id INT, body VARCHAR)");
        String tenK = "x".repeat(10_000);
        String hundredK = "é".repeat(50_000);  // 100,000 UTF-8 bytes
        db.execute("INSERT INTO docs VALUES (1, '" + tenK + "')");
        db.execute("INSERT INTO docs VALUES (2, '" + hundredK + "')");
        assertThat(db.execute("SELECT body FROM docs WHERE id = 1").rows().get(0).get("body")).isEqualTo(tenK);
        assertThat(db.execute("SELECT body FROM docs WHERE id = 2").rows().get(0).get("body")).isEqualTo(hundredK);
    }

    @Test
    @DisplayName("F-TYP-09 Scientific-notation DOUBLE literal")
    void scientificNotation() {
        db.execute("CREATE TABLE d (x DOUBLE)");
        db.execute("INSERT INTO d VALUES (1.5E3)");
        db.execute("INSERT INTO d VALUES (1e10)");
        assertThat(count(db, "SELECT COUNT(*) FROM d WHERE x = 1500")).isEqualTo(1);
        assertThat(count(db, "SELECT COUNT(*) FROM d WHERE x = 10000000000.0")).isEqualTo(1);
    }

    @Test
    @DisplayName("F-TYP-11/12 INT overflow and fractional values are rejected, not truncated")
    void intRangeAndFraction() {
        db.execute("CREATE TABLE n (i INT)");
        assertThatThrownBy(() -> db.execute("INSERT INTO n VALUES (99999999999)")).isNotNull();
        assertThatThrownBy(() -> db.execute("INSERT INTO n VALUES (10.9)")).isNotNull();
        assertThat(count(db, "SELECT COUNT(*) FROM n")).isZero();
    }

    @Test
    @DisplayName("F-DDL-11 Bad TIMESTAMP error message is formatted (no '%s')")
    void timestampErrorMessageFormatted() {
        db.execute("CREATE TABLE ts (t TIMESTAMP)");
        Throwable t = catchThrowable(() -> db.execute("INSERT INTO ts VALUES ('not a time')"));
        assertThat(t).isNotNull();
        assertThat(t.getMessage()).doesNotContain("%s").contains("not a time");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "F-SQL-01 INSERT with column list      | INSERT INTO customers (id, name) VALUES (500, 'cl')",
            "F-SQL-02 multi-row INSERT              | INSERT INTO scratch VALUES (1), (2), (3)",
            "F-SQL-03 OR                            | SELECT * FROM customers WHERE city = 'Pune' OR age > 50",
            "F-SQL-04 IN                            | SELECT * FROM customers WHERE id IN (1, 2, 3)",
            "F-SQL-05 LIKE                          | SELECT * FROM customers WHERE name LIKE 'c1%'",
            "F-SQL-06 BETWEEN                       | SELECT * FROM customers WHERE age BETWEEN 20 AND 30",
            "F-SQL-07 IS NULL                       | SELECT * FROM customers WHERE city IS NULL",
            "F-SQL-08 arithmetic in SET             | UPDATE customers SET balance = balance - 10 WHERE id = 1",
            "F-SQL-09 DISTINCT                      | SELECT DISTINCT city FROM customers",
            "F-SQL-10 HAVING                        | SELECT city, COUNT(*) FROM customers GROUP BY city HAVING COUNT(*) > 1",
            "F-SQL-11 subquery                      | SELECT * FROM customers WHERE age > (SELECT AVG(age) FROM customers)",
            "F-SQL-12 LEFT JOIN                     | SELECT c.name, o.amount FROM customers c LEFT JOIN orders o ON c.id = o.cid",
            "F-SQL-13 table alias                   | SELECT c.name FROM customers c",
            "F-SQL-14 PRIMARY KEY                   | CREATE TABLE pk (id INT PRIMARY KEY, v VARCHAR)",
            "F-SQL-15 NOT NULL                      | CREATE TABLE nn (id INT NOT NULL)",
            "F-SQL-16 two JOINs                     | SELECT c.name, i.sku FROM customers c JOIN orders o ON c.id = o.cid JOIN items i ON o.oid = i.oid",
            "F-SQL-17 GROUP BY two columns          | SELECT city, age, COUNT(*) FROM customers GROUP BY city, age",
            "F-SQL-21 double-quoted identifier      | SELECT \"name\" FROM customers",
            "F-SQL-22 IF NOT EXISTS                 | CREATE TABLE IF NOT EXISTS customers (id INT)",
            "F-SQL-23 TRUNCATE TABLE                | TRUNCATE TABLE scratch",
            "F-SQL-24 ALTER TABLE ADD COLUMN        | ALTER TABLE customers ADD COLUMN email VARCHAR",
            "F-SQL-25 SELECT without FROM           | SELECT 1",
            "F-SQL-26 NOT and <>                    | SELECT * FROM customers WHERE NOT (age > 30) AND city <> 'Pune'",
    })
    void dialectStatementsSupported(String id, String sql) {
        db.execute(sql);
    }

    @Test
    @DisplayName("F-SQL-* dialect features return correct results")
    void dialectResultsCorrect() {
        assertThat(count(db, "SELECT COUNT(*) FROM customers WHERE city = 'Pune' OR age > 50")).isEqualTo(10 + 6);
        assertThat(count(db, "SELECT COUNT(*) FROM customers WHERE id IN (1, 2, 3, 999)")).isEqualTo(3);
        assertThat(count(db, "SELECT COUNT(*) FROM customers WHERE name LIKE 'c1%'")).isEqualTo(11);
        assertThat(count(db, "SELECT COUNT(*) FROM customers WHERE age BETWEEN 20 AND 30")).isEqualTo(11);
        assertThat(db.execute("SELECT DISTINCT city FROM customers").rows()).hasSize(4);
        db.execute("UPDATE customers SET balance = balance - 10 WHERE id = 1");
        assertThat(E2e.number(db.execute("SELECT balance FROM customers WHERE id = 1")).doubleValue()).isEqualTo(0.0);
        assertThat(db.execute("SELECT c.name, o.amount FROM customers c LEFT JOIN orders o ON c.id = o.cid WHERE c.id > 20").rows())
                .hasSize(20);   // customers 21..40 have no orders: one NULL-extended row each
        assertThat(E2e.number(db.execute("SELECT 1 + 1")).intValue()).isEqualTo(2);
        db.execute("ALTER TABLE customers ADD COLUMN email VARCHAR");
        assertThat(db.execute("SELECT email FROM customers WHERE id = 1").rows().get(0).get("email")).isNull();
    }

    @Test
    @DisplayName("F-ERR-06 ORDER BY an unknown column is rejected")
    void orderByUnknownColumn() {
        assertThatThrownBy(() -> db.execute("SELECT id FROM customers ORDER BY nope")).isNotNull();
    }

    @Test
    @DisplayName("F-ERR-09 Keywords usable as column names (count unquoted, \"order\" quoted)")
    void keywordsAsColumnNames() {
        db.execute("CREATE TABLE kw (count INT, \"order\" INT)");
        db.execute("INSERT INTO kw VALUES (1, 2)");
        QueryResult r = db.execute("SELECT count, \"order\" FROM kw WHERE count = 1");
        assertThat(r.rows()).hasSize(1);
        assertThat(((Number) r.rows().get(0).get("order")).intValue()).isEqualTo(2);
    }
}
