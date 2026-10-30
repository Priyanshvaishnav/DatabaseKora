package dev.koradb.e2e;

import dev.koradb.KoraDB;
import dev.koradb.catalog.DiskCatalog;
import dev.koradb.replication.master.ReplicationMaster;
import dev.koradb.replication.master.ReplicationMasterConfig;
import dev.koradb.replication.model.ReplicationMode;
import dev.koradb.replication.replica.ReplicationReplica;
import dev.koradb.replication.replica.ReplicationReplicaConfig;
import dev.koradb.session.Session;
import dev.koradb.storage.wal.ClrLogRecord;
import dev.koradb.storage.wal.LogIterator;
import dev.koradb.storage.wal.LogManager;
import dev.koradb.storage.wal.LogRecord;
import dev.koradb.transaction.IsolationLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.koradb.e2e.E2e.bag;
import static dev.koradb.e2e.E2e.count;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A replica never exposes work the primary has not committed, and a promoted replica rolls it back.
 * The WAL is shared, so one transaction's commit also ships the records of transactions still open.
 */
class ReplicaTransactionsRegressionTest {

    private Path primaryDir, replicaDir;
    private KoraDB primary, replicaDb;
    private ReplicationMaster master;
    private ReplicationReplica replica;
    private final List<Process> children = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        primaryDir = Files.createTempDirectory("kora-e2e-tp");
        replicaDir = Files.createTempDirectory("kora-e2e-tr");
        primary = new KoraDB(primaryDir);
        primary.execute("CREATE DATABASE d");
        primary.execute("USE d");
        master = new ReplicationMaster(ReplicationMasterConfig.onPort(0), primaryLog(), x -> Map.of());
        master.start();
        primary.execute("CREATE TABLE t (id INT PRIMARY KEY, k INT, v VARCHAR)");
        primary.execute("CREATE INDEX t_k ON t (k)");
        primary.execute("CREATE TABLE marker (id INT)");
        for (int i = 1; i <= 10; i++) primary.execute("INSERT INTO t VALUES (" + i + ", " + i + ", 'committed')");
    }

    @AfterEach
    void tearDown() {
        children.forEach(Process::destroyForcibly);
        if (replica != null) replica.close();
        if (replicaDb != null) replicaDb.close();
        master.close();
        primary.close();
        E2e.deleteTree(primaryDir);
        E2e.deleteTree(replicaDir);
    }

    private LogManager primaryLog() {
        return ((DiskCatalog) primary.getCatalog()).getLogManager();
    }

    private void newReplica() throws Exception {
        replicaDb = new KoraDB(replicaDir);
        var catalog = (DiskCatalog) replicaDb.getCatalog();
        if (!catalog.listDatabases().contains("d")) catalog.createDatabase("d");
        catalog.useDatabase("d");
        replica = new ReplicationReplica(replicaDb, ReplicationReplicaConfig.of("r1", "127.0.0.1", master.getPort(), ReplicationMode.ASYNC));
    }

    private void startReplica() throws Exception {
        newReplica();
        replica.start();
    }

    private Session openTransaction() {
        Session s = primary.createSession();
        s.setDefaultIsolationLevel(IsolationLevel.SNAPSHOT);
        primary.execute(s, "USE d");
        primary.execute(s, "BEGIN");
        return s;
    }

    /** Commits something else: that flushes the shared WAL, so open transactions' records are shipped. */
    private void shipAndCatchUp() throws Exception {
        primary.execute("INSERT INTO marker VALUES (1)");
        long target = primaryLog().getFlushedLsn();
        long deadline = System.currentTimeMillis() + 20_000;
        while (replica.getWalApplier().getAppliedLsn() < target) {
            assertThat(System.currentTimeMillis()).as("catch-up to LSN " + target).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private void assertReplicaHasOnlyCommittedRows() {
        String all = "SELECT * FROM t";
        assertThat(bag(replicaDb.execute(all))).isEqualTo(bag(primary.execute(all)));
        for (int id : new int[]{1, 2, 3, 4, 100, 200}) {
            String sql = "SELECT * FROM t WHERE id = " + id;                // through the primary key index
            assertThat(bag(replicaDb.execute(sql))).as(sql).isEqualTo(bag(primary.execute(sql)));
        }
        for (int k : new int[]{1, 2, 3, 50}) {
            String sql = "SELECT * FROM t WHERE k = " + k;                  // through a secondary index
            assertThat(bag(replicaDb.execute(sql))).as(sql).isEqualTo(bag(primary.execute(sql)));
        }
    }

    private void dirtyWork(Session s) {
        primary.execute(s, "INSERT INTO t VALUES (100, 50, 'uncommitted insert')");
        primary.execute(s, "UPDATE t SET v = 'uncommitted update' WHERE id = 1");
        primary.execute(s, "UPDATE t SET k = 50 WHERE id = 2");             // moves a key
        primary.execute(s, "DELETE FROM t WHERE id = 3");
    }

    @Test
    @DisplayName("A transaction still open on the primary is invisible on the replica; its COMMIT shows all of it")
    void openTransactionInvisibleUntilCommit() throws Exception {
        startReplica();
        Session s = openTransaction();
        dirtyWork(s);
        shipAndCatchUp();
        assertReplicaHasOnlyCommittedRows();
        assertThat(count(replicaDb, "SELECT COUNT(*) FROM t WHERE v = 'uncommitted insert'")).isZero();

        primary.execute(s, "COMMIT");
        shipAndCatchUp();
        assertReplicaHasOnlyCommittedRows();
        assertThat(count(replicaDb, "SELECT COUNT(*) FROM t WHERE v = 'uncommitted insert'")).isEqualTo(1);
        assertThat(count(replicaDb, "SELECT COUNT(*) FROM t WHERE id = 3")).isZero();
    }

    @Test
    @DisplayName("A transaction rolled back on the primary is never visible on the replica")
    void rollbackNeverVisible() throws Exception {
        startReplica();
        Session s = openTransaction();
        dirtyWork(s);
        shipAndCatchUp();
        primary.execute(s, "ROLLBACK");
        shipAndCatchUp();
        assertReplicaHasOnlyCommittedRows();
        assertThat(replicaDb.execute("SELECT v FROM t WHERE id = 1").rows().get(0).get("v")).isEqualTo("committed");
    }

    @Test
    @DisplayName("A lower transaction id whose first change arrives after a higher id committed is still invisible")
    void lowerIdArrivingLate() throws Exception {
        startReplica();
        Session older = openTransaction();                                  // gets its id now ...
        primary.execute(older, "SELECT * FROM t");
        primary.execute("INSERT INTO t VALUES (101, 1, 'newer id, committed')");
        primary.execute(older, "INSERT INTO t VALUES (102, 1, 'older id, uncommitted')");   // ... writes later
        shipAndCatchUp();
        assertThat(replicaDb.execute("SELECT * FROM t WHERE id = 101").rows()).hasSize(1);
        assertThat(replicaDb.execute("SELECT * FROM t WHERE id = 102").rows()).isEmpty();
        assertReplicaHasOnlyCommittedRows();
    }

    @Test
    @DisplayName("A snapshot on the replica does not change when the primary commits, including a lower id")
    void snapshotStableOnReplica() throws Exception {
        Session older = openTransaction();
        primary.execute(older, "SELECT * FROM t");
        startReplica();
        primary.execute("INSERT INTO t VALUES (101, 1, 'newer id')");
        shipAndCatchUp();

        Session reader = replicaDb.createSession();
        reader.setDefaultIsolationLevel(IsolationLevel.SNAPSHOT);
        replicaDb.execute(reader, "USE d");
        replicaDb.execute(reader, "BEGIN");
        long before = count(replicaDb, "SELECT COUNT(*) FROM t");
        assertThat(((Number) replicaDb.execute(reader, "SELECT COUNT(*) FROM t").rows().get(0).values().values().iterator().next()).longValue()).isEqualTo(before);

        primary.execute(older, "INSERT INTO t VALUES (102, 1, 'older id')");
        primary.execute(older, "COMMIT");
        primary.execute("INSERT INTO t VALUES (103, 1, 'even newer')");
        shipAndCatchUp();

        assertThat(((Number) replicaDb.execute(reader, "SELECT COUNT(*) FROM t").rows().get(0).values().values().iterator().next()).longValue()).isEqualTo(before);
        replicaDb.execute(reader, "COMMIT");
        assertThat(count(replicaDb, "SELECT COUNT(*) FROM t")).isEqualTo(before + 2);
    }

    @Test
    @DisplayName("Promotion rolls back unfinished transactions: rows, keys, constraints and later writes are right")
    void promotionRollsBackOpenTransactions() throws Exception {
        startReplica();
        Session a = openTransaction();
        Session b = openTransaction();
        dirtyWork(a);
        primary.execute(b, "INSERT INTO t VALUES (200, 60, 'second open transaction')");
        primary.execute(b, "UPDATE t SET v = 'second' WHERE id = 4");
        primary.execute("INSERT INTO t VALUES (11, 11, 'committed meanwhile')");
        shipAndCatchUp();
        List<String> committed = bag(primary.execute("SELECT * FROM t"));   // a and b are invisible here too

        master.close();
        replica.promote();

        assertThat(bag(replicaDb.execute("SELECT * FROM t"))).isEqualTo(committed);
        assertThat(replicaDb.execute("SELECT * FROM t WHERE id = 100").rows()).isEmpty();
        assertThat(replicaDb.execute("SELECT * FROM t WHERE id = 200").rows()).isEmpty();
        assertThat(replicaDb.execute("SELECT v FROM t WHERE id = 1").rows().get(0).get("v")).isEqualTo("committed");
        assertThat(count(replicaDb, "SELECT COUNT(*) FROM t WHERE k = 2")).isEqualTo(1);
        assertThat(count(replicaDb, "SELECT COUNT(*) FROM t WHERE k = 50")).isZero();
        assertThat(replicaDb.execute("SELECT * FROM t WHERE id = 3").rows()).hasSize(1);

        // The rows are not locked by a dead transaction, and the keys it used are free.
        assertThat(replica.execute("UPDATE t SET v = 'mine' WHERE id = 1").affectedRows()).isEqualTo(1);
        assertThat(replica.execute("DELETE FROM t WHERE id = 3").affectedRows()).isEqualTo(1);
        replica.execute("INSERT INTO t VALUES (100, 1, 'key free again')");
        assertThat(replicaDb.execute("SELECT v FROM t WHERE id = 100").rows().get(0).get("v")).isEqualTo("key free again");

        List<String> finalState = bag(replicaDb.execute("SELECT * FROM t"));
        replica.close();
        replica = null;
        replicaDb.close();
        replicaDb = new KoraDB(replicaDir);
        replicaDb.execute("USE d");
        assertThat(bag(replicaDb.execute("SELECT * FROM t"))).as("after restart").isEqualTo(finalState);
        assertThat(replicaDb.execute("SELECT * FROM t WHERE id = 200").rows()).isEmpty();
    }

    @Test
    @DisplayName("Promotion finishes a rollback the primary died in the middle of")
    void promotionFinishesAnInterruptedRollback() throws Exception {
        Session s = openTransaction();
        primary.execute(s, "INSERT INTO t VALUES (100, 50, 'a')");
        primary.execute(s, "INSERT INTO t VALUES (101, 50, 'b')");
        primary.execute(s, "UPDATE t SET v = 'c' WHERE id = 1");
        primary.execute(s, "DELETE FROM t WHERE id = 3");
        primary.execute(s, "ROLLBACK");
        primary.execute("INSERT INTO marker VALUES (1)");

        // The WAL as a replica would have it if the primary had died right after its first CLR.
        List<LogRecord> records = new ArrayList<>();
        try (LogIterator it = new LogIterator(primaryLog().getWalPath(), LogManager.WAL_HEADER_SIZE)) {
            while (it.hasNext()) {
                LogRecord r = it.next();
                records.add(r);
                if (r instanceof ClrLogRecord) break;
            }
        }
        assertThat(records.get(records.size() - 1)).isInstanceOf(ClrLogRecord.class);

        newReplica();
        replica.getWalApplier().applyRecords(records);
        replica.promote();

        assertThat(bag(replicaDb.execute("SELECT * FROM t"))).isEqualTo(bag(primary.execute("SELECT * FROM t")));
    }

    @Test
    @DisplayName("A restarted replica learns about open transactions before it serves reads")
    void replicaRestartWithOpenTransaction() throws Exception {
        startReplica();
        Session s = openTransaction();
        dirtyWork(s);
        shipAndCatchUp();
        replica.close();
        replica = null;
        replicaDb.close();

        startReplica();                                                     // returns once caught up
        assertReplicaHasOnlyCommittedRows();

        primary.execute(s, "COMMIT");
        shipAndCatchUp();
        assertReplicaHasOnlyCommittedRows();
        assertThat(replicaDb.execute("SELECT * FROM t WHERE id = 100").rows()).hasSize(1);
    }

    @Test
    @DisplayName("After kill -9 of a node promoted with open transactions, only committed work is there")
    void promotedWithOpenTransactionThenCrash() throws Exception {
        primary.execute("UPDATE t SET v = 'x' WHERE id = 7");
        Session s = openTransaction();
        primary.execute(s, "INSERT INTO t VALUES (9000, 1, 'uncommitted')");
        primary.execute(s, "UPDATE t SET v = 'uncommitted' WHERE id = 8");
        primary.execute("INSERT INTO marker VALUES (1)");

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), ReplicaWorkload.class.getName(),
                replicaDir.toString(), String.valueOf(master.getPort()), "10", "promote").redirectErrorStream(true).start();
        children.add(p);
        try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            boolean ready = false;
            StringBuilder seen = new StringBuilder();
            while ((line = out.readLine()) != null) {
                if (line.contains("READY")) { ready = true; break; }
                seen.append(line).append('\n');
            }
            assertThat(ready).as("child reached READY; output:\n" + seen).isTrue();
        }
        p.destroyForcibly();
        p.waitFor(30, TimeUnit.SECONDS);

        replicaDb = new KoraDB(replicaDir);
        replicaDb.execute("USE d");
        assertThat(replicaDb.execute("SELECT * FROM t WHERE id = 9000").rows()).isEmpty();
        assertThat(replicaDb.execute("SELECT v FROM t WHERE id = 8").rows().get(0).get("v")).isEqualTo("committed");
        assertThat(replicaDb.execute("SELECT v FROM t WHERE id = 7").rows().get(0).get("v")).isEqualTo("x");
        assertThat(count(replicaDb, "SELECT COUNT(*) FROM t WHERE v = 'uncommitted'")).isZero();
        // What the promoted node itself committed (see ReplicaWorkload)
        assertThat(replicaDb.execute("SELECT * FROM t WHERE id = 9001").rows()).hasSize(1);
        assertThat(replicaDb.execute("SELECT v FROM t WHERE id = 5").rows().get(0).get("v")).isEqualTo("changed after promotion");
        assertThat(replicaDb.execute("SELECT * FROM t WHERE id = 6").rows()).isEmpty();
    }

    @Test
    @DisplayName("Under concurrent transfers and rollbacks, a replica's readers always see the exact total")
    void replicaReadersSeeConsistentTotals() throws Exception {
        primary.execute("CREATE TABLE acct (id INT PRIMARY KEY, bal INT)");
        for (int i = 0; i < 10; i++) primary.execute("INSERT INTO acct VALUES (" + i + ", 1000)");
        startReplica();
        shipAndCatchUp();

        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger committed = new AtomicInteger(), reads = new AtomicInteger();
        List<Thread> writers = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            long seed = w;
            writers.add(Thread.ofPlatform().start(() -> {
                Random r = new Random(seed);
                Session s = primary.createSession();
                s.setDefaultIsolationLevel(IsolationLevel.SNAPSHOT);
                primary.execute(s, "USE d");
                while (!stop.get()) {
                    int from = r.nextInt(10), to = (from + 1 + r.nextInt(9)) % 10;
                    try {
                        primary.execute(s, "BEGIN");
                        primary.execute(s, "UPDATE acct SET bal = bal - 1 WHERE id = " + from);
                        Thread.yield();
                        primary.execute(s, "UPDATE acct SET bal = bal + 1 WHERE id = " + to);
                        if (r.nextInt(4) == 0) {
                            primary.execute(s, "ROLLBACK");
                        } else {
                            primary.execute(s, "COMMIT");
                            committed.incrementAndGet();
                        }
                    } catch (dev.koradb.mvcc.WriteConflictException conflict) {
                        // the engine has already rolled it back
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        return;
                    }
                }
            }));
        }
        Thread reader = Thread.ofPlatform().start(() -> {
            try {
                while (!stop.get()) {
                    assertThat(count(replicaDb, "SELECT SUM(bal) FROM acct")).isEqualTo(10_000);
                    assertThat(count(replicaDb, "SELECT COUNT(*) FROM acct")).isEqualTo(10);
                    reads.incrementAndGet();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        Thread.sleep(4000);
        stop.set(true);
        for (Thread t : writers) t.join(30_000);
        reader.join(30_000);

        assertThat(failure.get()).as("first failure").isNull();
        assertThat(committed.get()).as("transfers committed").isPositive();
        assertThat(reads.get()).as("reads made").isPositive();
        shipAndCatchUp();
        assertThat(bag(replicaDb.execute("SELECT * FROM acct"))).isEqualTo(bag(primary.execute("SELECT * FROM acct")));
    }
}
