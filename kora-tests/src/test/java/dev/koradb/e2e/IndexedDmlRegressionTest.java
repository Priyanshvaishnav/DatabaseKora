package dev.koradb.e2e;

import dev.koradb.KoraDB;
import dev.koradb.execution.IndexLookup;
import dev.koradb.execution.QueryResult;
import dev.koradb.session.Session;
import dev.koradb.sql.ast.DeleteStmt;
import dev.koradb.sql.ast.WhereClause;
import dev.koradb.sql.lexer.Lexer;
import dev.koradb.sql.parser.Parser;
import dev.koradb.transaction.IsolationLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static dev.koradb.e2e.E2e.bag;
import static dev.koradb.e2e.E2e.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lookups through an index: SELECT on a unique key after updates, and UPDATE / DELETE using indexes. */
class IndexedDmlRegressionTest {

    private Path dir;
    private KoraDB db;

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("kora-e2e-idx");
        db = new KoraDB(dir);
        db.execute("CREATE DATABASE shop");
        db.execute("USE shop");
        db.execute("CREATE TABLE c (id INT PRIMARY KEY, k INT, name VARCHAR, credit DOUBLE)");
        db.execute("CREATE INDEX c_k ON c (k)");
        db.execute("CREATE INDEX c_name ON c (name)");
        StringBuilder sb = new StringBuilder("INSERT INTO c VALUES ");
        for (int i = 1; i <= 200; i++) {
            if (i > 1) sb.append(", ");
            sb.append('(').append(i).append(", ").append(i % 10).append(", 'n").append(i % 4).append("', 100.0)");
        }
        db.execute(sb.toString());
    }

    @AfterEach
    void tearDown() {
        db.close();
        E2e.deleteTree(dir);
    }

    private Object credit(int id) {
        List<dev.koradb.catalog.Row> rows = db.execute("SELECT credit FROM c WHERE id = " + id).rows();
        assertThat(rows).as("rows for id " + id).hasSize(1);
        return rows.get(0).get("credit");
    }

    // ------------------------------------------------------------------ SELECT on a unique key

    @Test
    @DisplayName("SELECT by primary key finds a row that has been updated, however many times")
    void selectByPrimaryKeyAfterUpdates() {
        for (int i = 1; i <= 5; i++) {
            db.execute("UPDATE c SET credit = credit + 1 WHERE id = 7");
            assertThat(credit(7)).isEqualTo(100.0 + i);
        }
        db.execute("UPDATE c SET credit = credit + 1 WHERE name = 'n3'");   // by another column
        assertThat(credit(7)).isEqualTo(106.0);
    }

    @Test
    @DisplayName("SELECT by a UNIQUE index: moved key, rolled-back update, deleted row")
    void selectByUniqueKeyThroughRowLifecycle() {
        db.execute("UPDATE c SET id = 1000 WHERE id = 8");
        assertThat(db.execute("SELECT * FROM c WHERE id = 8").rows()).isEmpty();
        assertThat(credit(1000)).isEqualTo(100.0);

        db.execute("BEGIN");
        db.execute("UPDATE c SET credit = 1 WHERE id = 9");
        assertThat(credit(9)).isEqualTo(1.0);
        db.execute("ROLLBACK");
        assertThat(credit(9)).isEqualTo(100.0);

        db.execute("DELETE FROM c WHERE id = 10");
        assertThat(db.execute("SELECT * FROM c WHERE id = 10").rows()).isEmpty();
        db.execute("INSERT INTO c VALUES (10, 0, 'again', 5.0)");           // the key is free again
        assertThat(credit(10)).isEqualTo(5.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id = 7.5", "id = 4294967303", "k = 7.5"})
    @DisplayName("A literal with no exact key never selects the truncated key's rows")
    void inexactLiteralsMatchNothing(String predicate) {
        assertThat(db.execute("SELECT * FROM c WHERE " + predicate).rows()).isEmpty();
        assertThat(db.execute("UPDATE c SET credit = 0 WHERE " + predicate).affectedRows()).isZero();
        assertThat(db.execute("DELETE FROM c WHERE " + predicate).affectedRows()).isZero();
    }

    @Test
    @DisplayName("Comparing an indexed text column to a number is a type error, as on an unindexed column")
    void typeMismatchOnIndexedColumn() {
        assertThatThrownBy(() -> db.execute("SELECT * FROM c WHERE name = 5"))
                .isInstanceOf(dev.koradb.exception.ExecutionException.class).hasMessageContaining("Type mismatch");
        assertThatThrownBy(() -> db.execute("UPDATE c SET credit = 0 WHERE name = 5"))
                .isInstanceOf(dev.koradb.exception.ExecutionException.class).hasMessageContaining("Type mismatch");
    }

    @Test
    @DisplayName("A whole-number double literal still matches an integer key")
    void wholeDoubleLiteral() {
        assertThat(db.execute("SELECT * FROM c WHERE id = 7.0").rows()).hasSize(1);
        assertThat(db.execute("UPDATE c SET credit = 1 WHERE id = 7.0").affectedRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("TIMESTAMP index: every literal form INSERT accepts works for SELECT, UPDATE and DELETE")
    void timestampIndex() {
        db.execute("CREATE TABLE ev (id INT, at TIMESTAMP)");
        db.execute("CREATE INDEX ev_at ON ev (at)");
        db.execute("INSERT INTO ev VALUES (1, '2024-01-01 00:00:00'), (2, '2024-01-01 10:30:00')");
        assertThat(db.execute("SELECT id FROM ev WHERE at = '2024-01-01'").rows()).hasSize(1);
        assertThat(db.execute("SELECT id FROM ev WHERE at = '2024-01-01T10:30'").rows()).hasSize(1);
        assertThat(db.execute("UPDATE ev SET id = 3 WHERE at = '2024-01-01 10:30'").affectedRows()).isEqualTo(1);
        assertThat(db.execute("DELETE FROM ev WHERE at = '2024-01-01'").affectedRows()).isEqualTo(1);
        assertThat(count(db, "SELECT COUNT(*) FROM ev")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the index is actually chosen

    private WhereClause where(String predicate) {
        return ((DeleteStmt) new Parser(new Lexer("DELETE FROM c WHERE " + predicate).tokenize()).parse()).where().orElseThrow();
    }

    private java.util.Optional<List<dev.koradb.storage.RecordId>> candidates(String predicate) {
        return IndexLookup.candidates("c", where(predicate), db.getDefaultSession().getCatalog());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id = 7", "7 = id", "c.id = 7", "ID = 7", "id = 7 AND credit > 0", "credit > 0 AND id = 7",
            "k = 3 AND id = 7", "id IN (7)", "id IN (7, NULL)", "id = 7 AND name = 'nope'"})
    @DisplayName("UPDATE / DELETE read one row, not the table, when a condition hits the unique index")
    void pointLookupUsesTheIndex(String predicate) {
        assertThat(candidates(predicate)).isPresent();
        assertThat(candidates(predicate).get()).hasSize(1);
    }

    @Test
    @DisplayName("Non-unique index and IN read only the matching keys")
    void nonUniqueLookupsUseTheIndex() {
        assertThat(candidates("k = 3").orElseThrow()).hasSize(20);
        assertThat(candidates("name = 'n1'").orElseThrow()).hasSize(50);
        assertThat(candidates("id IN (1, 2, 3, 999)").orElseThrow()).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"credit = 100.0", "id > 7", "id <> 7", "id + 0 = 7", "id = 7 OR k = 3", "NOT id = 7",
            "id NOT IN (7)", "id = 7.5", "id = '7'", "other.id = 7", "id IN (1, 2 + 1)"})
    @DisplayName("Conditions an index cannot answer exactly fall back to a table scan")
    void otherConditionsScan(String predicate) {
        assertThat(candidates(predicate)).isEmpty();
    }

    // ------------------------------------------------------------------ UPDATE / DELETE results

    @Test
    @DisplayName("UPDATE and DELETE by index return correct row counts and keep every other row")
    void dmlByIndex() {
        assertThat(db.execute("UPDATE c SET credit = 1 WHERE k = 3").affectedRows()).isEqualTo(20);
        assertThat(count(db, "SELECT COUNT(*) FROM c WHERE credit = 1")).isEqualTo(20);
        assertThat(db.execute("UPDATE c SET credit = 2 WHERE k = 3 AND name = 'n1'").affectedRows()).isEqualTo(10);
        assertThat(db.execute("UPDATE c SET credit = 3 WHERE id IN (1, 2, 3, 999)").affectedRows()).isEqualTo(3);
        assertThat(db.execute("DELETE FROM c WHERE id = 4").affectedRows()).isEqualTo(1);
        assertThat(db.execute("DELETE FROM c WHERE id = 4").affectedRows()).isZero();
        assertThat(db.execute("DELETE FROM c WHERE k = 5 AND credit = 100").affectedRows()).isEqualTo(20);
        assertThat(count(db, "SELECT COUNT(*) FROM c")).isEqualTo(200 - 1 - 20);
    }

    @Test
    @DisplayName("An UPDATE that changes the key it searched by visits each row once")
    void updateOfTheSearchedKey() {
        assertThat(db.execute("UPDATE c SET k = k + 1 WHERE k = 3").affectedRows()).isEqualTo(20);
        assertThat(count(db, "SELECT COUNT(*) FROM c WHERE k = 3")).isZero();
        assertThat(count(db, "SELECT COUNT(*) FROM c WHERE k = 4")).isEqualTo(40);
        assertThat(db.execute("UPDATE c SET k = k WHERE k = 4").affectedRows()).isEqualTo(40);
        assertThat(count(db, "SELECT COUNT(*) FROM c WHERE k = 4")).isEqualTo(40);
    }

    @Test
    @DisplayName("Inside a transaction UPDATE sees its own changes through the index; ROLLBACK restores them")
    void ownChangesAndRollback() {
        db.execute("BEGIN");
        assertThat(db.execute("UPDATE c SET k = 77 WHERE k = 1").affectedRows()).isEqualTo(20);
        assertThat(db.execute("UPDATE c SET name = 'seen' WHERE k = 77").affectedRows()).isEqualTo(20);
        assertThat(db.execute("UPDATE c SET name = 'stale' WHERE k = 1").affectedRows()).isZero();
        assertThat(db.execute("DELETE FROM c WHERE k = 77").affectedRows()).isEqualTo(20);
        db.execute("ROLLBACK");
        assertThat(count(db, "SELECT COUNT(*) FROM c WHERE k = 1")).isEqualTo(20);
        assertThat(db.execute("UPDATE c SET credit = 9 WHERE k = 1").affectedRows()).isEqualTo(20);
        assertThat(db.execute("UPDATE c SET credit = 9 WHERE k = 77").affectedRows()).isZero();
    }

    @Test
    @DisplayName("A stale snapshot still finds the old key through the index and gets a write conflict, not 0 rows")
    void staleSnapshotConflicts() {
        Session a = db.createSession();
        a.setDefaultIsolationLevel(IsolationLevel.SNAPSHOT);
        db.execute(a, "USE shop");
        db.execute(a, "BEGIN");
        assertThat(db.execute(a, "SELECT * FROM c WHERE k = 2").rows()).hasSize(20);    // fixes the snapshot

        db.execute("UPDATE c SET k = 88 WHERE id = 2");                                 // committed by someone else

        assertThat(db.execute(a, "SELECT * FROM c WHERE k = 2").rows()).hasSize(20);
        assertThat(db.execute(a, "SELECT * FROM c WHERE k = 88").rows()).isEmpty();
        assertThatThrownBy(() -> db.execute(a, "UPDATE c SET credit = 0 WHERE id = 2"))
                .isInstanceOf(dev.koradb.mvcc.WriteConflictException.class);
    }

    @Test
    @DisplayName("Index lookups stay correct after VACUUM reclaims old versions and their slots are reused")
    void afterVacuum() {
        for (int i = 0; i < 5; i++) db.execute("UPDATE c SET credit = credit + 1 WHERE k = 6");
        db.execute("DELETE FROM c WHERE k = 7");
        db.vacuum();
        StringBuilder sb = new StringBuilder("INSERT INTO c VALUES ");
        for (int i = 0; i < 50; i++) {
            if (i > 0) sb.append(", ");
            sb.append('(').append(1000 + i).append(", 7, 'reused', 1.0)");
        }
        db.execute(sb.toString());
        assertThat(db.execute("UPDATE c SET credit = 0 WHERE k = 7").affectedRows()).isEqualTo(50);
        assertThat(db.execute("UPDATE c SET credit = 0 WHERE k = 6").affectedRows()).isEqualTo(20);
        assertThat(credit(6)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Changes made through the index survive a clean restart")
    void afterRestart() {
        db.execute("UPDATE c SET credit = 5 WHERE id = 3");
        db.execute("DELETE FROM c WHERE id = 4");
        db.close();
        db = new KoraDB(dir);
        db.execute("USE shop");
        assertThat(credit(3)).isEqualTo(5.0);
        assertThat(db.execute("SELECT * FROM c WHERE id = 4").rows()).isEmpty();
        assertThat(db.execute("UPDATE c SET credit = 6 WHERE id = 3").affectedRows()).isEqualTo(1);
        assertThat(credit(3)).isEqualTo(6.0);
    }

    @Test
    @DisplayName("Random DML by key gives the same table as the same DML on an unindexed twin")
    void differentialAgainstUnindexedTwin() {
        for (String t : List.of("u_i", "u_n")) {
            db.execute("CREATE TABLE " + t + " (id INT, k INT, s VARCHAR, v DOUBLE)");
        }
        db.execute("CREATE UNIQUE INDEX u_i_id ON u_i (id)");
        db.execute("CREATE INDEX u_i_k ON u_i (k)");
        db.execute("CREATE INDEX u_i_s ON u_i (s)");
        Random r = new Random(3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            if (i > 0) sb.append(", ");
            sb.append('(').append(i).append(", ").append(r.nextInt(40)).append(", 's").append(r.nextInt(25)).append("', ").append(r.nextInt(1000)).append(".5)");
        }
        for (String t : List.of("u_i", "u_n")) db.execute("INSERT INTO " + t + " VALUES " + sb);

        int nextId = 1000;
        for (int step = 0; step < 400; step++) {
            String pred = switch (r.nextInt(5)) {
                case 0 -> "id = " + r.nextInt(nextId);
                case 1 -> "k = " + r.nextInt(40);
                case 2 -> "s = 's" + r.nextInt(25) + "'";
                case 3 -> "k = " + r.nextInt(40) + " AND v > " + r.nextInt(1000);
                default -> "id IN (" + r.nextInt(nextId) + ", " + r.nextInt(nextId) + ", -1)";
            };
            String sql = switch (r.nextInt(5)) {
                case 0 -> "UPDATE %s SET v = v + 1 WHERE " + pred;
                case 1 -> "UPDATE %s SET k = " + r.nextInt(40) + " WHERE " + pred;
                case 2 -> "UPDATE %s SET s = 's" + r.nextInt(25) + "' WHERE " + pred;
                case 3 -> "DELETE FROM %s WHERE " + pred + " AND v < 200";
                default -> "INSERT INTO %s VALUES (" + nextId++ + ", " + r.nextInt(40) + ", 'new', 1.5)";
            };
            int inIndexed = db.execute(sql.formatted("u_i")).affectedRows();
            int inTwin = db.execute(sql.formatted("u_n")).affectedRows();
            assertThat(inIndexed).as(sql).isEqualTo(inTwin);
            if (step % 40 == 0) {
                assertThat(bag(db.execute("SELECT * FROM u_i"))).isEqualTo(bag(db.execute("SELECT * FROM u_n")));
            }
        }
        assertThat(bag(db.execute("SELECT * FROM u_i"))).isEqualTo(bag(db.execute("SELECT * FROM u_n")));
        for (int id = 0; id < nextId; id += 7) {
            QueryResult viaKey = db.execute("SELECT * FROM u_i WHERE id = " + id);
            QueryResult viaScan = db.execute("SELECT * FROM u_n WHERE id = " + id);
            assertThat(bag(viaKey)).as("id " + id).isEqualTo(bag(viaScan));
        }
    }
}
