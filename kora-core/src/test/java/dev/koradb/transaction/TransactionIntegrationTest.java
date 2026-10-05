package dev.koradb.transaction;

import dev.koradb.KoraDB;
import dev.koradb.execution.QueryResult;
import dev.koradb.session.Session;
import dev.koradb.transaction.lock.DeadlockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Transaction End-to-End Integration Tests")
class TransactionIntegrationTest {

    private KoraDB db;

    @BeforeEach
    void setUp() {
        db = new KoraDB();
        db.execute("CREATE DATABASE bank");
        db.execute("USE bank");
        db.execute("CREATE TABLE accounts (id INT, name VARCHAR, balance DOUBLE)");
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("BEGIN -> INSERT -> ROLLBACK restores empty table state")
    void testInsertRollback() {
        db.execute("BEGIN");
        db.execute("INSERT INTO accounts VALUES (1, 'Alice', 1000.0)");

        // Row visible inside transaction
        QueryResult inside = db.execute("SELECT * FROM accounts");
        assertThat(inside.rows()).hasSize(1);

        db.execute("ROLLBACK");

        // Row rolled back
        QueryResult after = db.execute("SELECT * FROM accounts");
        assertThat(after.rows()).isEmpty();
    }

    @Test
    @DisplayName("BEGIN -> INSERT -> COMMIT persists inserted row")
    void testInsertCommit() {
        db.execute("BEGIN");
        db.execute("INSERT INTO accounts VALUES (1, 'Alice', 1000.0)");
        db.execute("COMMIT");

        QueryResult after = db.execute("SELECT * FROM accounts");
        assertThat(after.rows()).hasSize(1);
        assertThat(after.rows().get(0).get("name")).isEqualTo("Alice");
    }

    @Test
    @DisplayName("BEGIN -> UPDATE -> ROLLBACK restores original values")
    void testUpdateRollback() {
        db.execute("INSERT INTO accounts VALUES (1, 'Alice', 1000.0)");

        db.execute("BEGIN");
        db.execute("UPDATE accounts SET balance = 5000.0 WHERE id = 1");

        QueryResult inside = db.execute("SELECT * FROM accounts WHERE id = 1");
        assertThat(inside.rows().get(0).get("balance")).isEqualTo(5000.0);

        db.execute("ROLLBACK");

        QueryResult after = db.execute("SELECT * FROM accounts WHERE id = 1");
        assertThat(after.rows().get(0).get("balance")).isEqualTo(1000.0);
    }

    @Test
    @DisplayName("BEGIN -> UPDATE -> COMMIT persists updated values")
    void testUpdateCommit() {
        db.execute("INSERT INTO accounts VALUES (1, 'Alice', 1000.0)");

        db.execute("BEGIN");
        db.execute("UPDATE accounts SET balance = 5000.0 WHERE id = 1");
        db.execute("COMMIT");

        QueryResult after = db.execute("SELECT * FROM accounts WHERE id = 1");
        assertThat(after.rows().get(0).get("balance")).isEqualTo(5000.0);
    }

    @Test
    @DisplayName("BEGIN -> DELETE -> ROLLBACK restores deleted rows")
    void testDeleteRollback() {
        db.execute("INSERT INTO accounts VALUES (1, 'Alice', 1000.0)");
        db.execute("INSERT INTO accounts VALUES (2, 'Bob', 2000.0)");

        db.execute("BEGIN");
        db.execute("DELETE FROM accounts WHERE id = 1");

        QueryResult inside = db.execute("SELECT * FROM accounts");
        assertThat(inside.rows()).hasSize(1);
        assertThat(inside.rows().get(0).get("name")).isEqualTo("Bob");

        db.execute("ROLLBACK");

        QueryResult after = db.execute("SELECT * FROM accounts");
        assertThat(after.rows()).hasSize(2);
    }

    @Test
    @DisplayName("BEGIN -> DELETE -> COMMIT persists row deletions")
    void testDeleteCommit() {
        db.execute("INSERT INTO accounts VALUES (1, 'Alice', 1000.0)");

        db.execute("BEGIN");
        db.execute("DELETE FROM accounts WHERE id = 1");
        db.execute("COMMIT");

        QueryResult after = db.execute("SELECT * FROM accounts");
        assertThat(after.rows()).isEmpty();
    }

    @Test
    @DisplayName("Autocommit mode persists individual statements automatically")
    void testAutocommit() {
        // Without explicit BEGIN
        db.execute("INSERT INTO accounts VALUES (1, 'Alice', 1000.0)");

        QueryResult res = db.execute("SELECT * FROM accounts");
        assertThat(res.rows()).hasSize(1);
        assertThat(db.getDefaultSession().hasActiveTransaction()).isFalse();
    }

    @Test
    @DisplayName("Concurrent sessions: SS2PL blocks conflicting reader until writer commits")
    void testStrict2PLReaderWaitsForWriter() throws Exception {
        Session session1 = db.createSession();
        Session session2 = db.createSession();

        CountDownLatch writerLocked = new CountDownLatch(1);
        CountDownLatch readerStarted = new CountDownLatch(1);
        AtomicBoolean readerSawCommittedRow = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Writer thread (session1): BEGIN; INSERT; wait; COMMIT
        Future<?> writerFuture = executor.submit(() -> {
            try {
                db.execute(session1, "BEGIN");
                db.execute(session1, "INSERT INTO accounts VALUES (1, 'Alice', 1000.0)");
                writerLocked.countDown(); // notify reader that writer holds exclusive lock

                readerStarted.await(); // wait for reader to attempt read
                Thread.sleep(100);     // keep reader waiting
                db.execute(session1, "COMMIT");
            } catch (Exception e) {
                // unexpected
            }
        });

        // Reader thread (session2): attempts SELECT, should block until writer commits
        Future<?> readerFuture = executor.submit(() -> {
            try {
                writerLocked.await(); // wait until writer is in transaction
                readerStarted.countDown();
                QueryResult res = db.execute(session2, "SELECT * FROM accounts");
                if (res.rows().size() == 1 && "Alice".equals(res.rows().get(0).get("name"))) {
                    readerSawCommittedRow.set(true);
                }
            } catch (Exception e) {
                // unexpected
            }
        });

        writerFuture.get(3, TimeUnit.SECONDS);
        readerFuture.get(3, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(readerSawCommittedRow.get()).isTrue();
    }

    @Test
    @DisplayName("Deadlock integration: circular wait across sessions triggers DeadlockException and aborts")
    void testDeadlockAcrossSessions() throws Exception {
        db.execute("CREATE TABLE orders (id INT, account_id INT, amount DOUBLE)");

        // Set low deadlock timeout for fast test execution
        db.getLockManager().setDefaultTimeoutMs(300);

        Session session1 = db.createSession();
        Session session2 = db.createSession();

        CountDownLatch step1 = new CountDownLatch(2);
        AtomicBoolean s1Deadlocked = new AtomicBoolean(false);
        AtomicBoolean s2Deadlocked = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Session 1: locks accounts, then tries to lock orders
        Future<?> f1 = executor.submit(() -> {
            try {
                db.execute(session1, "BEGIN");
                db.execute(session1, "INSERT INTO accounts VALUES (10, 'UserA', 500.0)");
                step1.countDown();
                step1.await();

                Thread.sleep(50);
                db.execute(session1, "INSERT INTO orders VALUES (1, 10, 50.0)");
                db.execute(session1, "COMMIT");
            } catch (DeadlockException e) {
                s1Deadlocked.set(true);
            } catch (Exception e) {
                // unexpected
            }
        });

        // Session 2: locks orders, then tries to lock accounts
        Future<?> f2 = executor.submit(() -> {
            try {
                db.execute(session2, "BEGIN");
                db.execute(session2, "INSERT INTO orders VALUES (2, 20, 100.0)");
                step1.countDown();
                step1.await();

                Thread.sleep(50);
                db.execute(session2, "INSERT INTO accounts VALUES (20, 'UserB', 800.0)");
                db.execute(session2, "COMMIT");
            } catch (DeadlockException e) {
                s2Deadlocked.set(true);
            } catch (Exception e) {
                // unexpected
            }
        });

        f1.get(3, TimeUnit.SECONDS);
        f2.get(3, TimeUnit.SECONDS);
        executor.shutdown();

        // At least one session encountered DeadlockException and was cleanly aborted
        assertThat(s1Deadlocked.get() || s2Deadlocked.get()).isTrue();
    }

    @Test
    @DisplayName("Disk-backed transactions: rollback restores disk pages; commit persists across restarts")
    void testDiskTransactions(@TempDir Path tempDir) {
        try (KoraDB diskDb = new KoraDB(tempDir)) {
            diskDb.execute("CREATE DATABASE prod");
            diskDb.execute("USE prod");
            diskDb.execute("CREATE TABLE products (id INT, name VARCHAR, price DOUBLE)");
            diskDb.execute("INSERT INTO products VALUES (1, 'Laptop', 1200.0)");

            // Rollback test on disk
            diskDb.execute("BEGIN");
            diskDb.execute("INSERT INTO products VALUES (2, 'Mouse', 25.0)");
            diskDb.execute("ROLLBACK");

            QueryResult res1 = diskDb.execute("SELECT * FROM products");
            assertThat(res1.rows()).hasSize(1);
            assertThat(res1.rows().get(0).get("name")).isEqualTo("Laptop");

            // Commit test on disk
            diskDb.execute("BEGIN");
            diskDb.execute("INSERT INTO products VALUES (3, 'Keyboard', 75.0)");
            diskDb.execute("COMMIT");

            QueryResult res2 = diskDb.execute("SELECT * FROM products");
            assertThat(res2.rows()).hasSize(2);
        }

        // Restart from same directory to verify disk persistence
        try (KoraDB reopenedDb = new KoraDB(tempDir)) {
            reopenedDb.execute("USE prod");
            QueryResult res = reopenedDb.execute("SELECT * FROM products");
            assertThat(res.rows()).hasSize(2);
        }
    }
}
