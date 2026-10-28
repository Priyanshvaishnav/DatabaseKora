package dev.koradb.e2e;

import dev.koradb.client.KoraClient;
import dev.koradb.replication.model.NodeRole;
import dev.koradb.server.ServerInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Transactions rows of the end-to-end report that were FAIL or WARN, run over TCP/JDBC on a disk server. */
class TransactionsRegressionTest {

    private Path dir;
    private ServerInstance node;

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("kora-e2e-tx");
        node = Servers.start(NodeRole.STANDALONE, dir, false);
        node.getDb().execute("CREATE DATABASE tx_bank");
        try (Connection c = Servers.jdbc(node, "tx_bank"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE accounts (id INT, balance INT)");
            for (int i = 0; i < 20; i++) s.executeUpdate("INSERT INTO accounts VALUES (" + i + ", 1000)");
        }
    }

    @AfterEach
    void tearDown() {
        if (node != null) node.stop();
        E2e.deleteTree(dir);
    }

    private long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("T-ACID-04 ROLLBACK restores DELETEd rows")
    void rollbackRestoresDeletes() throws Exception {
        try (Connection c = Servers.jdbc(node, "tx_bank"); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.executeUpdate("DELETE FROM accounts WHERE id >= 10");
            c.rollback();
            c.setAutoCommit(true);
            assertThat(scalar(c, "SELECT COUNT(*) FROM accounts")).isEqualTo(20);
        }
    }

    @Test
    @DisplayName("T-ACID-12 Unique violation inside a txn, then COMMIT keeps only the valid rows")
    void uniqueViolationInsideTxn() throws Exception {
        try (Connection c = Servers.jdbc(node, "tx_bank"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE u (k INT)");
            s.execute("CREATE UNIQUE INDEX u_k ON u (k)");
            c.setAutoCommit(false);
            s.executeUpdate("INSERT INTO u VALUES (1)");
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO u VALUES (1)")).isInstanceOf(SQLException.class);
            c.commit();
            c.setAutoCommit(true);
            assertThat(scalar(c, "SELECT COUNT(*) FROM u")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("T-ISO-08 getTransactionIsolation reflects setTransactionIsolation")
    void isolationRoundTrip() throws Exception {
        try (Connection c = Servers.jdbc(node, "tx_bank")) {
            for (int level : new int[]{Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ,
                    Connection.TRANSACTION_SERIALIZABLE}) {
                c.setTransactionIsolation(level);
                assertThat(c.getTransactionIsolation()).as("level " + level).isEqualTo(level);
            }
        }
    }

    @Test
    @DisplayName("T-CON-03 After ROLLBACK the row is writable again by another transaction")
    void rowWritableAfterRollback() throws Exception {
        try (Connection a = Servers.jdbc(node, "tx_bank"); Connection b = Servers.jdbc(node, "tx_bank");
             Statement sa = a.createStatement(); Statement sb = b.createStatement()) {
            a.setAutoCommit(false);
            sa.executeUpdate("UPDATE accounts SET balance = 1 WHERE id = 3");
            a.rollback();
            assertThat(sb.executeUpdate("UPDATE accounts SET balance = 2 WHERE id = 3")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("T-CON-05 After a write conflict the losing connection is usable and rollback() is a no-op")
    void loserUsableAfterConflict() throws Exception {
        try (Connection a = Servers.jdbc(node, "tx_bank"); Connection b = Servers.jdbc(node, "tx_bank");
             Statement sa = a.createStatement(); Statement sb = b.createStatement()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            sa.executeUpdate("UPDATE accounts SET balance = balance - 1 WHERE id = 5");
            SQLException conflict = null;
            try {
                sb.executeUpdate("UPDATE accounts SET balance = balance - 1 WHERE id = 5");
            } catch (SQLException e) {
                conflict = e;
            }
            assertThat((Throwable) conflict).as("second writer must be rejected").isNotNull();
            b.rollback();                       // must not throw although the server already aborted
            a.commit();
            b.setAutoCommit(true);
            assertThat(scalar(b, "SELECT SUM(balance) FROM accounts")).isEqualTo(20_000 - 1);
        }
    }

    @Test
    @DisplayName("T-CON-07 A connection closed mid-transaction is rolled back and does not block others")
    void closedConnectionRolledBack() throws Exception {
        Connection a = Servers.jdbc(node, "tx_bank");
        a.setAutoCommit(false);
        a.createStatement().executeUpdate("UPDATE accounts SET balance = 0 WHERE id = 7");
        a.close();
        try (Connection b = Servers.jdbc(node, "tx_bank"); Statement sb = b.createStatement()) {
            assertThat(updateWithin(sb, "UPDATE accounts SET balance = 7 WHERE id = 7", 5000)).isEqualTo(1);
            assertThat(scalar(b, "SELECT balance FROM accounts WHERE id = 7")).isEqualTo(7);
        }
    }

    @Test
    @DisplayName("T-CON-08 A client socket killed mid-transaction (no disconnect message) releases its rows")
    void killedSocketRolledBack() throws Exception {
        KoraClient a = KoraClient.connect("127.0.0.1", node.getServer().getPort(), "tx_bank");
        a.execute("BEGIN");
        a.execute("UPDATE accounts SET balance = 0 WHERE id = 8");
        var f = KoraClient.class.getDeclaredField("socket");
        f.setAccessible(true);
        ((Socket) f.get(a)).close();            // abrupt: the server never receives DISCONNECT
        try (Connection b = Servers.jdbc(node, "tx_bank"); Statement sb = b.createStatement()) {
            assertThat(updateWithin(sb, "UPDATE accounts SET balance = 8 WHERE id = 8", 5000)).isEqualTo(1);
        }
    }

    /** Retries a statement that conflicts with a transaction the server is about to clean up. */
    private static int updateWithin(Statement s, String sql, long millis) throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (true) {
            try {
                return s.executeUpdate(sql);
            } catch (SQLException e) {
                if (System.currentTimeMillis() > deadline) throw e;
                Thread.sleep(100);
            }
        }
    }

    @Test
    @DisplayName("T-STR-02 Concurrent inserts from 16 connections: no lost rows, no errors")
    void concurrentInsertsNoLostRows() throws Exception {
        try (Connection c = Servers.jdbc(node, "tx_bank"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE ev (t INT, n INT)");
        }
        int threads = 16, perThread = 150;
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            jobs.add(() -> {
                try (Connection c = Servers.jdbc(node, "tx_bank"); Statement s = c.createStatement()) {
                    for (int i = 0; i < perThread; i++) s.executeUpdate("INSERT INTO ev VALUES (" + id + ", " + i + ")");
                }
                return null;
            });
        }
        runAll(jobs, 180);
        try (Connection c = Servers.jdbc(node, "tx_bank")) {
            assertThat(scalar(c, "SELECT COUNT(*) FROM ev")).isEqualTo((long) threads * perThread);
        }
    }

    @Test
    @DisplayName("T-STR-01 16 threads x 60 random transfers (retry on conflict) all commit and preserve the total")
    void concurrentTransfersMakeProgress() throws Exception {
        int threads = 16, transfers = 60;
        AtomicInteger committed = new AtomicInteger();
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            long seed = t;
            jobs.add(() -> {
                Random rnd = new Random(seed);
                try (Connection c = Servers.jdbc(node, "tx_bank"); Statement s = c.createStatement()) {
                    c.setAutoCommit(false);
                    for (int i = 0; i < transfers; i++) {
                        int from = rnd.nextInt(20), to = (from + 1 + rnd.nextInt(19)) % 20, amt = 1 + rnd.nextInt(5);
                        while (true) {
                            try {
                                s.executeUpdate("UPDATE accounts SET balance = balance - " + amt + " WHERE id = " + from);
                                s.executeUpdate("UPDATE accounts SET balance = balance + " + amt + " WHERE id = " + to);
                                c.commit();
                                committed.incrementAndGet();
                                break;
                            } catch (SQLException conflict) {
                                c.rollback();
                                Thread.sleep(rnd.nextInt(5));
                            }
                        }
                    }
                }
                return null;
            });
        }
        runAll(jobs, 240);
        assertThat(committed.get()).isEqualTo(threads * transfers);
        try (Connection c = Servers.jdbc(node, "tx_bank")) {
            assertThat(scalar(c, "SELECT SUM(balance) FROM accounts")).isEqualTo(20_000);
            assertThat(scalar(c, "SELECT COUNT(*) FROM accounts")).isEqualTo(20);
        }
    }

    @Test
    @DisplayName("T-MDB-00 8 clients connecting simultaneously to the same database all succeed")
    void simultaneousConnects() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            jobs.add(() -> {
                go.await();
                try (Connection c = Servers.jdbc(node, "tx_bank")) {
                    assertThat(scalar(c, "SELECT COUNT(*) FROM accounts")).isEqualTo(20);
                }
                return null;
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Void>> fs = new ArrayList<>();
            for (Callable<Void> j : jobs) fs.add(pool.submit(j));
            go.countDown();
            for (Future<Void> f : fs) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("T-MDB-02/04 Concurrent clients on two databases: every write lands in its own database")
    void tenantIsolation() throws Exception {
        node.getDb().execute("CREATE DATABASE tenant_a");
        node.getDb().execute("CREATE DATABASE tenant_b");
        for (String t : List.of("tenant_a", "tenant_b")) {
            try (Connection c = Servers.jdbc(node, t); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE notes (id INT, who VARCHAR)");
            }
        }
        List<Callable<Void>> jobs = new ArrayList<>();
        for (String t : List.of("tenant_a", "tenant_b")) {
            for (int k = 0; k < 3; k++) {
                jobs.add(() -> {
                    try (Connection c = Servers.jdbc(node, t); Statement s = c.createStatement()) {
                        for (int i = 0; i < 40; i++) s.executeUpdate("INSERT INTO notes VALUES (" + i + ", '" + t + "')");
                    }
                    return null;
                });
            }
        }
        runAll(jobs, 120);
        for (String t : List.of("tenant_a", "tenant_b")) {
            try (Connection c = Servers.jdbc(node, t)) {
                assertThat(scalar(c, "SELECT COUNT(*) FROM notes")).as(t).isEqualTo(120);
                assertThat(scalar(c, "SELECT COUNT(*) FROM notes WHERE who <> '" + t + "'")).as(t).isZero();
            }
        }
    }

    @Test
    @DisplayName("T-MDB-03 An open transaction survives another client switching database")
    void txnSurvivesOtherClientsUse() throws Exception {
        node.getDb().execute("CREATE DATABASE iso_beta");
        try (Connection a = Servers.jdbc(node, "tx_bank"); Statement sa = a.createStatement();
             Connection b = Servers.jdbc(node, "tx_bank"); Statement sb = b.createStatement()) {
            a.setAutoCommit(false);
            sa.executeUpdate("UPDATE accounts SET balance = balance - 100 WHERE id = 16");
            sb.execute("USE iso_beta");
            sa.executeUpdate("UPDATE accounts SET balance = balance + 100 WHERE id = 17");
            a.commit();
            a.setAutoCommit(true);
            assertThat(scalar(a, "SELECT SUM(balance) FROM accounts")).isEqualTo(20_000);
            assertThat(scalar(a, "SELECT balance FROM accounts WHERE id = 17")).isEqualTo(1100);
        }
    }

    @Test
    @DisplayName("T-MISC-02 DDL inside an open transaction is rejected instead of silently surviving ROLLBACK")
    void ddlInsideTxnRejected() throws Exception {
        // DDL is not transactional. Inside an open transaction it is refused with a clear error, and
        // the transaction's DML still rolls back; it never half-applies.
        try (Connection c = Servers.jdbc(node, "tx_bank"); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.executeUpdate("UPDATE accounts SET balance = 0 WHERE id = 1");
            assertThatThrownBy(() -> s.execute("CREATE TABLE ghost (x INT)"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("cannot run inside a transaction");
            c.rollback();
            c.setAutoCommit(true);
            assertThatThrownBy(() -> s.executeQuery("SELECT * FROM ghost")).isInstanceOf(SQLException.class);
            assertThat(scalar(c, "SELECT balance FROM accounts WHERE id = 1")).isEqualTo(1000);
        }
    }

    @Test
    @DisplayName("JDBC with autoCommit=false runs ALTER TABLE and TRUNCATE (statements added after the report)")
    void alterAndTruncateWithoutAutoCommit() throws Exception {
        try (Connection c = Servers.jdbc(node, "tx_bank"); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("ALTER TABLE accounts ADD COLUMN note VARCHAR");
            s.executeUpdate("TRUNCATE TABLE accounts");
            c.rollback();                       // TRUNCATE is transactional, ALTER is not
            c.setAutoCommit(true);
            assertThat(scalar(c, "SELECT COUNT(*) FROM accounts")).isEqualTo(20);
            try (ResultSet rs = s.executeQuery("SELECT note FROM accounts WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isNull();
            }
        }
    }

    @Test
    @DisplayName("T-REC-01 The database stays openable after a transactional workload with rollbacks (and a restart)")
    void openableAfterRollbackWorkload() throws Exception {
        try (Connection c = Servers.jdbc(node, "tx_bank"); Statement s = c.createStatement()) {
            for (int r = 0; r < 30; r++) {
                c.setAutoCommit(false);
                s.executeUpdate("INSERT INTO accounts VALUES (" + (100 + r) + ", 5)");
                s.executeUpdate("UPDATE accounts SET balance = balance + 1 WHERE id = " + (r % 20));
                s.executeUpdate("DELETE FROM accounts WHERE id = " + ((r + 7) % 20));
                if (r % 2 == 0) c.rollback(); else c.commit();
            }
            c.setAutoCommit(true);
        }
        long before;
        try (Connection c = Servers.jdbc(node, "tx_bank")) {
            before = scalar(c, "SELECT COUNT(*) FROM accounts");
        }
        node.stop();
        node = Servers.start(NodeRole.STANDALONE, dir, false);
        for (int i = 0; i < 3; i++) {
            try (Connection c = Servers.jdbc(node, "tx_bank")) {
                assertThat(scalar(c, "SELECT COUNT(*) FROM accounts")).isEqualTo(before);
            }
        }
    }

    private static void runAll(List<Callable<Void>> jobs, int timeoutSeconds) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        try {
            List<Future<Void>> fs = new ArrayList<>();
            for (Callable<Void> j : jobs) fs.add(pool.submit(j));
            for (Future<Void> f : fs) {
                try {
                    f.get(timeoutSeconds, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    errors.add(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(errors).isEmpty();
    }
}
