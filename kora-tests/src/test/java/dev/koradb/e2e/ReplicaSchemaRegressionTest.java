package dev.koradb.e2e;

import dev.koradb.KoraDB;
import dev.koradb.catalog.DiskCatalog;
import dev.koradb.index.Index;
import dev.koradb.replication.master.ReplicationMaster;
import dev.koradb.replication.master.ReplicationMasterConfig;
import dev.koradb.replication.model.ReplicationMode;
import dev.koradb.replication.replica.ReplicationReplica;
import dev.koradb.replication.replica.ReplicationReplicaConfig;
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
import java.util.concurrent.TimeUnit;

import static dev.koradb.e2e.E2e.bag;
import static dev.koradb.e2e.E2e.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A replica gets the primary's whole schema: constraints, indexes, drops; and keeps it through restarts and crashes. */
class ReplicaSchemaRegressionTest {

    private Path primaryDir, replicaDir;
    private KoraDB primary, replicaDb;
    private ReplicationMaster master;
    private ReplicationReplica replica;
    private final List<Process> children = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        primaryDir = Files.createTempDirectory("kora-e2e-rp");
        replicaDir = Files.createTempDirectory("kora-e2e-rr");
        primary = new KoraDB(primaryDir);
        primary.execute("CREATE DATABASE d");
        primary.execute("USE d");
        master = new ReplicationMaster(ReplicationMasterConfig.onPort(0),
                ((DiskCatalog) primary.getCatalog()).getLogManager(), x -> Map.of());
        master.start();
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

    private void startReplica() throws Exception {
        replicaDb = new KoraDB(replicaDir);
        var catalog = (DiskCatalog) replicaDb.getCatalog();
        if (!catalog.listDatabases().contains("d")) catalog.createDatabase("d");
        catalog.useDatabase("d");
        replica = new ReplicationReplica(replicaDb, ReplicationReplicaConfig.of("r1", "127.0.0.1", master.getPort(), ReplicationMode.ASYNC));
        replica.start();
    }

    private void stopReplica() {
        replica.close();
        replica = null;
        replicaDb.close();
        replicaDb = null;
    }

    /** Waits until the replica has applied everything the primary has flushed so far. */
    private void awaitCatchUp() throws Exception {
        long target = ((DiskCatalog) primary.getCatalog()).getLogManager().getFlushedLsn();
        long deadline = System.currentTimeMillis() + 20_000;
        while (replica.getWalApplier().getAppliedLsn() < target) {
            assertThat(System.currentTimeMillis()).as("replica catch-up to LSN " + target).isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private Index replicaIndex(String name) {
        return replicaDb.getCatalog().getIndex(name).orElseThrow(() -> new AssertionError("no index " + name + " on replica"));
    }

    private String plan(KoraDB db, String sql) {
        return db.execute("EXPLAIN " + sql).toDisplayString();
    }

    @Test
    @DisplayName("PRIMARY KEY, UNIQUE and CREATE INDEX reach the replica, which uses them for reads")
    void indexesReachTheReplica() throws Exception {
        startReplica();
        primary.execute("CREATE TABLE t (id INT PRIMARY KEY, email VARCHAR UNIQUE, k INT, v VARCHAR)");
        for (int i = 0; i < 100; i++) primary.execute("INSERT INTO t VALUES (" + i + ", 'e" + i + "', " + i % 5 + ", 'v')");
        primary.execute("CREATE INDEX t_k ON t (k)");                                       // covers existing rows
        for (int i = 100; i < 150; i++) primary.execute("INSERT INTO t VALUES (" + i + ", 'e" + i + "', " + i % 5 + ", 'v')");
        primary.execute("UPDATE t SET v = 'edited' WHERE id = 7");
        primary.execute("UPDATE t SET k = 99 WHERE id = 8");                                // new key
        primary.execute("UPDATE t SET id = 1000 WHERE id = 9");
        primary.execute("DELETE FROM t WHERE id = 10");
        primary.execute("BEGIN");
        primary.execute("INSERT INTO t VALUES (2000, 'ghost', 1, 'rolled back')");
        primary.execute("UPDATE t SET k = 98 WHERE id = 11");
        primary.execute("ROLLBACK");
        awaitCatchUp();

        assertThat(replicaIndex("t_pkey").isUnique()).isTrue();
        assertThat(replicaIndex("t_email_key").isUnique()).isTrue();
        assertThat(replicaIndex("t_k").isUnique()).isFalse();
        assertThat(plan(replicaDb, "SELECT * FROM t WHERE id = 7")).contains("IndexScan");

        assertThat(replicaDb.execute("SELECT v FROM t WHERE id = 7").rows().get(0).get("v")).isEqualTo("edited");
        assertThat(replicaDb.execute("SELECT * FROM t WHERE id = 9").rows()).isEmpty();
        assertThat(replicaDb.execute("SELECT * FROM t WHERE id = 1000").rows()).hasSize(1);
        assertThat(replicaDb.execute("SELECT * FROM t WHERE id = 10").rows()).isEmpty();
        assertThat(replicaDb.execute("SELECT * FROM t WHERE k = 99").rows()).hasSize(1);
        assertThat(replicaDb.execute("SELECT * FROM t WHERE id = 2000").rows()).isEmpty();
        assertThat(replicaDb.execute("SELECT * FROM t WHERE k = 98").rows()).isEmpty();
        for (int id = 0; id < 150; id++) {
            String sql = "SELECT * FROM t WHERE id = " + id;
            assertThat(bag(replicaDb.execute(sql))).as(sql).isEqualTo(bag(primary.execute(sql)));
        }
        for (int k = 0; k < 5; k++) {
            String sql = "SELECT * FROM t WHERE k = " + k;
            assertThat(bag(replicaDb.execute(sql))).as(sql).isEqualTo(bag(primary.execute(sql)));
        }
        String mail = "SELECT id FROM t WHERE email = 'e20'";
        assertThat(bag(replicaDb.execute(mail))).isEqualTo(bag(primary.execute(mail)));
    }

    @Test
    @DisplayName("A promoted replica enforces PRIMARY KEY and UNIQUE; its UPDATE and DELETE use the indexes")
    void promotedReplicaEnforcesConstraints() throws Exception {
        startReplica();
        primary.execute("CREATE TABLE t (id INT PRIMARY KEY, email VARCHAR UNIQUE, k INT)");
        for (int i = 1; i <= 20; i++) primary.execute("INSERT INTO t VALUES (" + i + ", 'e" + i + "', 0)");
        awaitCatchUp();
        replica.promote();

        assertThatThrownBy(() -> replica.execute("INSERT INTO t VALUES (1, 'new', 0)")).hasMessageContaining("nique");
        assertThatThrownBy(() -> replica.execute("INSERT INTO t VALUES (100, 'e1', 0)")).hasMessageContaining("nique");
        assertThatThrownBy(() -> replica.execute("UPDATE t SET id = 2 WHERE id = 1")).hasMessageContaining("nique");
        assertThat(count(replicaDb, "SELECT COUNT(*) FROM t")).isEqualTo(20);

        assertThat(replica.execute("UPDATE t SET k = 5 WHERE id = 3").affectedRows()).isEqualTo(1);
        assertThat(replica.execute("DELETE FROM t WHERE id = 4").affectedRows()).isEqualTo(1);
        replica.execute("INSERT INTO t VALUES (4, 'e4', 7)");                               // the key is free again
        assertThat(replicaDb.execute("SELECT k FROM t WHERE id = 4").rows().get(0).get("k")).isEqualTo(7);
    }

    @Test
    @DisplayName("DROP TABLE and DROP INDEX reach the replica, and a recreated table starts empty")
    void dropsReachTheReplica() throws Exception {
        startReplica();
        primary.execute("CREATE TABLE gone (id INT PRIMARY KEY, v INT)");
        primary.execute("CREATE TABLE keep (id INT, v INT)");
        primary.execute("CREATE INDEX keep_v ON keep (v)");
        primary.execute("INSERT INTO gone VALUES (1, 1)");
        primary.execute("INSERT INTO keep VALUES (1, 1)");
        awaitCatchUp();
        assertThat(replicaDb.getCatalog().getIndex("keep_v")).isPresent();
        assertThat(replicaDb.getCatalog().getIndex("gone_pkey")).isPresent();

        primary.execute("DROP INDEX keep_v");
        primary.execute("DROP TABLE gone");
        awaitCatchUp();
        assertThat(replicaDb.getCatalog().getIndex("keep_v")).isEmpty();
        assertThat(replicaDb.getCatalog().getIndex("gone_pkey")).isEmpty();
        assertThatThrownBy(() -> replicaDb.execute("SELECT * FROM gone")).hasMessageContaining("does not exist");
        assertThat(count(replicaDb, "SELECT COUNT(*) FROM keep WHERE v = 1")).isEqualTo(1);

        primary.execute("CREATE TABLE gone (id INT, other VARCHAR)");
        primary.execute("INSERT INTO gone VALUES (5, 'second life')");
        awaitCatchUp();
        assertThat(bag(replicaDb.execute("SELECT * FROM gone"))).isEqualTo(bag(primary.execute("SELECT * FROM gone")));
    }

    @Test
    @DisplayName("Keyword and spaced names survive replication")
    void quotedNames() throws Exception {
        startReplica();
        primary.execute("CREATE TABLE \"Total Amount\" (\"order\" INT PRIMARY KEY, \"group\" VARCHAR NOT NULL)");
        primary.execute("CREATE INDEX \"by group\" ON \"Total Amount\" (\"group\")");
        primary.execute("INSERT INTO \"Total Amount\" VALUES (1, 'a')");
        primary.execute("ALTER TABLE \"Total Amount\" ADD COLUMN \"select\" INT");
        primary.execute("INSERT INTO \"Total Amount\" VALUES (2, 'b', 9)");
        awaitCatchUp();

        String sql = "SELECT * FROM \"Total Amount\" WHERE \"order\" = 2";
        assertThat(bag(replicaDb.execute(sql))).hasSize(1).isEqualTo(bag(primary.execute(sql)));
        assertThat(replicaDb.getCatalog().getIndex("by group")).isPresent();
        assertThat(count(replicaDb, "SELECT COUNT(*) FROM \"Total Amount\" WHERE \"group\" = 'a'")).isEqualTo(1);
        assertThatThrownBy(() -> replicaDb.execute("INSERT INTO \"Total Amount\" VALUES (3, NULL, 0)")).hasMessageContaining("NOT NULL");
    }

    @Test
    @DisplayName("A restarted replica converges, even when the history it receives again drops and recreates a table")
    void replicaRestart() throws Exception {
        startReplica();
        primary.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
        primary.execute("INSERT INTO t VALUES (1, 1), (2, 2)");
        primary.execute("DROP TABLE t");
        primary.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT, extra VARCHAR)");
        primary.execute("CREATE INDEX t_v ON t (v)");
        primary.execute("INSERT INTO t VALUES (10, 1, 'x'), (20, 2, 'y'), (30, 2, 'z')");
        primary.execute("UPDATE t SET v = 3 WHERE id = 20");
        primary.execute("CREATE TABLE other (id INT)");
        primary.execute("CREATE UNIQUE INDEX other_id ON other (id)");
        primary.execute("DROP INDEX other_id");
        primary.execute("INSERT INTO other VALUES (1), (1)");
        awaitCatchUp();
        stopReplica();

        primary.execute("INSERT INTO t VALUES (40, 4, 'while away')");
        startReplica();
        awaitCatchUp();

        assertThat(bag(replicaDb.execute("SELECT * FROM t"))).isEqualTo(bag(primary.execute("SELECT * FROM t")));
        for (int id : new int[]{1, 2, 10, 20, 30, 40}) {
            String sql = "SELECT * FROM t WHERE id = " + id;
            assertThat(bag(replicaDb.execute(sql))).as(sql).isEqualTo(bag(primary.execute(sql)));
        }
        assertThat(count(replicaDb, "SELECT COUNT(*) FROM t WHERE v = 2")).isEqualTo(1);
        assertThat(count(replicaDb, "SELECT COUNT(*) FROM other")).isEqualTo(2);
        assertThat(replicaDb.getCatalog().getIndex("other_id")).isEmpty();
    }

    private Process child(Path dir, long rows, String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), ReplicaWorkload.class.getName(),
                dir.toString(), String.valueOf(master.getPort()), String.valueOf(rows), mode).redirectErrorStream(true).start();
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
        p.destroyForcibly();                       // kill -9
        p.waitFor(30, TimeUnit.SECONDS);
        return p;
    }

    private void loadForCrash() {
        primary.execute("CREATE TABLE t (id INT PRIMARY KEY, k INT, v VARCHAR)");
        primary.execute("CREATE INDEX t_k ON t (k)");
        StringBuilder sb = new StringBuilder("INSERT INTO t VALUES ");
        for (int i = 0; i < 500; i++) {
            if (i > 0) sb.append(", ");
            sb.append('(').append(i).append(", ").append(i % 7).append(", 'v").append(i).append("')");
        }
        primary.execute(sb.toString());
        primary.execute("UPDATE t SET k = 50 WHERE id = 3");
    }

    @Test
    @DisplayName("After kill -9 of a replica its indexes are complete again when it restarts")
    void replicaCrash() throws Exception {
        loadForCrash();
        child(replicaDir, 500, "replica");
        primary.execute("INSERT INTO t VALUES (600, 1, 'after the crash')");
        startReplica();
        awaitCatchUp();

        for (int id : new int[]{0, 3, 250, 499, 600}) {
            String sql = "SELECT * FROM t WHERE id = " + id;
            assertThat(bag(replicaDb.execute(sql))).as(sql).hasSize(1).isEqualTo(bag(primary.execute(sql)));
        }
        for (int k = 0; k < 7; k++) {
            String sql = "SELECT * FROM t WHERE k = " + k;
            assertThat(bag(replicaDb.execute(sql))).as(sql).isEqualTo(bag(primary.execute(sql)));
        }
    }

    @Test
    @DisplayName("After kill -9 of a promoted replica, replicated and own committed changes survive, with constraints")
    void promotedReplicaCrash() throws Exception {
        loadForCrash();
        child(replicaDir, 500, "promote");

        replicaDb = new KoraDB(replicaDir);
        replicaDb.execute("USE d");
        assertThat(count(replicaDb, "SELECT COUNT(*) FROM t")).as("500 replicated + 1 inserted - 1 deleted").isEqualTo(500);
        assertThat(replicaDb.execute("SELECT * FROM t WHERE id = 9001").rows()).hasSize(1);
        assertThat(replicaDb.execute("SELECT v FROM t WHERE id = 5").rows().get(0).get("v")).isEqualTo("changed after promotion");
        assertThat(replicaDb.execute("SELECT * FROM t WHERE id = 6").rows()).isEmpty();
        assertThat(replicaDb.execute("SELECT * FROM t WHERE id = 250").rows()).hasSize(1);
        assertThat(count(replicaDb, "SELECT COUNT(*) FROM t WHERE k = 50")).isEqualTo(1);
        assertThatThrownBy(() -> replicaDb.execute("INSERT INTO t VALUES (250, 0, 'duplicate')")).hasMessageContaining("nique");
    }
}
