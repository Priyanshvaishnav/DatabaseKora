package dev.koradb.e2e;

import dev.koradb.KoraDB;
import dev.koradb.catalog.DiskCatalog;
import dev.koradb.replication.master.ReplicationMaster;
import dev.koradb.replication.master.ReplicationMasterConfig;
import dev.koradb.replication.model.ReplicationMode;
import dev.koradb.replication.replica.ReplicationReplica;
import dev.koradb.replication.replica.ReplicationReplicaConfig;
import dev.koradb.session.Session;
import org.junit.jupiter.api.AfterEach;
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

import static dev.koradb.e2e.E2e.bag;
import static dev.koradb.e2e.E2e.count;
import static org.assertj.core.api.Assertions.assertThat;

/** Durability, Bulk-Data, Differential and Replication rows of the end-to-end report that were FAIL or WARN. */
class DurabilityAndBulkRegressionTest {

    private final List<Path> dirs = new ArrayList<>();
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (int i = open.size() - 1; i >= 0; i--) {
            try { open.get(i).close(); } catch (Exception ignored) {}
        }
        dirs.forEach(E2e::deleteTree);
    }

    private Path tempDir() throws Exception {
        Path d = Files.createTempDirectory("kora-e2e-dur");
        dirs.add(d);
        return d;
    }

    private KoraDB openDb(Path dir) {
        KoraDB db = new KoraDB(dir);
        open.add(db);
        return db;
    }

    @Test
    @DisplayName("D-08 After kill -9 and recovery, index lookups agree with a full scan")
    void indexAgreesWithScanAfterCrash() throws Exception {
        Path dir = tempDir();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                CrashWorkload.class.getName(), dir.toString()).redirectErrorStream(true).start();
        try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            boolean ready = false;
            while ((line = out.readLine()) != null) {
                if (line.contains("READY")) { ready = true; break; }
            }
            assertThat(ready).as("child workload reached READY").isTrue();
        } finally {
            p.destroyForcibly();                   // kill -9
            p.waitFor(30, TimeUnit.SECONDS);
        }

        KoraDB db = openDb(dir);
        db.execute("USE crash");
        assertThat(count(db, "SELECT COUNT(*) FROM t")).isEqualTo(280);
        assertThat(count(db, "SELECT COUNT(*) FROM t WHERE v = 'uncommitted'")).isZero();
        long[] viaIndex = new long[100];
        for (int k = 0; k < 100; k++) viaIndex[k] = count(db, "SELECT COUNT(*) FROM t WHERE k = " + k);
        // ids 3, 33, ... 273, minus id 3 (committed to k = 99); recovery rebuilt the index UPDATE now uses
        assertThat(db.execute("UPDATE t SET v = 'after-crash' WHERE k = 3").affectedRows()).isEqualTo(9);
        db.execute("DROP INDEX t_k");
        for (int k = 0; k < 100; k++) {
            assertThat(viaIndex[k]).as("k = " + k).isEqualTo(count(db, "SELECT COUNT(*) FROM t WHERE k = " + k));
        }
        assertThat(count(db, "SELECT COUNT(*) FROM t WHERE k = 99")).isEqualTo(20);
        assertThat(count(db, "SELECT COUNT(*) FROM t WHERE k = 77")).isZero();
    }

    @Test
    @DisplayName("B-IDX-02/03 Indexed equality on high-cardinality duplicates (1500 rows per key, index maintained during load)")
    void indexedEqualityOnHeavyDuplicates() throws Exception {
        KoraDB db = openDb(tempDir());
        db.execute("CREATE DATABASE iot");
        db.execute("USE iot");
        db.execute("CREATE TABLE readings (id INT, device INT, val DOUBLE)");
        db.execute("CREATE INDEX readings_device ON readings (device)");
        int id = 0;
        for (int batch = 0; batch < 30; batch++) {
            StringBuilder sb = new StringBuilder("INSERT INTO readings VALUES ");
            for (int i = 0; i < 500; i++, id++) {
                if (i > 0) sb.append(", ");
                sb.append('(').append(id).append(", ").append(id % 10).append(", ").append(id * 0.5).append(')');
            }
            db.execute(sb.toString());
        }
        for (int d = 0; d < 10; d++) {
            assertThat(count(db, "SELECT COUNT(*) FROM readings WHERE device = " + d)).as("device " + d).isEqualTo(1500);
        }
        assertThat(count(db, "SELECT COUNT(*) FROM readings WHERE device >= 5")).isEqualTo(7500);
    }

    @Test
    @DisplayName("DIFF-INDEXED / DIFF-AFTER-DML Random queries give identical results with and without indexes, also after DML")
    void indexedVersusUnindexedDifferential() throws Exception {
        KoraDB db = openDb(tempDir());
        db.execute("CREATE DATABASE diff");
        db.execute("USE diff");
        for (String t : List.of("emp_i", "emp_n")) {
            db.execute("CREATE TABLE " + t + " (id INT, dept INT, name VARCHAR, city VARCHAR, salary DOUBLE, age INT, active BOOLEAN, big BIGINT)");
        }
        for (String c : List.of("city", "age", "name", "dept", "active")) {
            db.execute("CREATE INDEX emp_i_" + c + " ON emp_i (" + c + ")");
        }
        String[] cities = {"Pune", "Delhi", "Goa", "O''Hare", "Zürich", "Mumbai"};
        Random rnd = new Random(7);
        for (int batch = 0; batch < 6; batch++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 500; i++) {
                int idv = batch * 500 + i;
                if (i > 0) sb.append(", ");
                sb.append('(').append(idv).append(", ").append(rnd.nextInt(30)).append(", 'n").append(rnd.nextInt(400))
                        .append("', '").append(cities[rnd.nextInt(cities.length)]).append("', ")
                        .append(Math.round(rnd.nextDouble() * 9_000_000) / 100.0).append(", ").append(18 + rnd.nextInt(42))
                        .append(", ").append(rnd.nextBoolean()).append(", ").append(rnd.nextLong(1L << 40)).append(')');
            }
            for (String t : List.of("emp_i", "emp_n")) db.execute("INSERT INTO " + t + " VALUES " + sb);
        }

        List<String> mismatches = new ArrayList<>();
        compareRandomQueries(db, new Random(11), 300, cities, mismatches);
        for (int i = 0; i < 60; i++) {
            String where = randomPredicate(rnd, cities);
            String dml = switch (i % 3) {
                case 0 -> "UPDATE %s SET age = age + 1, city = 'Goa' WHERE " + where;
                case 1 -> "DELETE FROM %s WHERE " + where + " AND id %% 7 = 0";
                default -> "UPDATE %s SET name = 'n" + rnd.nextInt(400) + "' WHERE " + where;
            };
            for (String t : List.of("emp_i", "emp_n")) db.execute(dml.formatted(t));
        }
        compareRandomQueries(db, new Random(13), 300, cities, mismatches);
        assertThat(mismatches).isEmpty();
    }

    private static void compareRandomQueries(KoraDB db, Random rnd, int n, String[] cities, List<String> mismatches) {
        String[] selects = {"id, name, salary", "COUNT(*)", "SUM(salary)", "age, MIN(id)", "active, SUM(salary)", "dept, big, active, city"};
        for (int q = 0; q < n; q++) {
            String sel = selects[rnd.nextInt(selects.length)];
            String sql = "SELECT " + sel + " FROM %s WHERE " + randomPredicate(rnd, cities);
            if (sel.startsWith("age,")) sql += " GROUP BY age";
            if (sel.startsWith("active,")) sql += " GROUP BY active";
            if (sel.startsWith("id,") && rnd.nextBoolean()) sql += " ORDER BY id DESC LIMIT 21";
            List<String> a = bag(db.execute(sql.formatted("emp_i")));
            List<String> b = bag(db.execute(sql.formatted("emp_n")));
            if (!a.equals(b)) mismatches.add(sql.formatted("emp") + " -> indexed " + a.size() + " rows, unindexed " + b.size());
        }
    }

    private static String randomPredicate(Random rnd, String[] cities) {
        List<String> preds = new ArrayList<>();
        int k = 1 + rnd.nextInt(3);
        for (int i = 0; i < k; i++) {
            preds.add(switch (rnd.nextInt(6)) {
                case 0 -> "city = '" + cities[rnd.nextInt(cities.length)] + "'";
                case 1 -> "age " + new String[]{"=", "<", ">=", "!="}[rnd.nextInt(4)] + " " + (18 + rnd.nextInt(42));
                case 2 -> "name >= 'n" + rnd.nextInt(400) + "'";
                case 3 -> "dept = " + rnd.nextInt(30);
                case 4 -> "active = " + rnd.nextBoolean();
                default -> "salary <= " + Math.round(rnd.nextDouble() * 9_000_000) / 100.0;
            });
        }
        return String.join(" AND ", preds);
    }

    @Test
    @DisplayName("B-PERF-02 Opening a new session on a database with a large WAL is fast (no WAL replay per USE)")
    void connectLatency() throws Exception {
        KoraDB db = openDb(tempDir());
        db.execute("CREATE DATABASE retail");
        db.execute("USE retail");
        db.execute("CREATE TABLE orders (id INT, amount DOUBLE)");
        for (int batch = 0; batch < 20; batch++) {
            StringBuilder sb = new StringBuilder("INSERT INTO orders VALUES ");
            for (int i = 0; i < 500; i++) {
                if (i > 0) sb.append(", ");
                sb.append('(').append(batch * 500 + i).append(", ").append(i * 1.5).append(')');
            }
            db.execute(sb.toString());
        }
        db.execute("UPDATE orders SET amount = amount + 1");
        long worst = 0;
        for (int i = 0; i < 10; i++) {
            long t0 = System.nanoTime();
            Session s = db.createSession();
            db.execute(s, "USE retail");
            worst = Math.max(worst, (System.nanoTime() - t0) / 1_000_000);
        }
        assertThat(worst).as("worst USE latency in ms").isLessThan(500);
    }

    @Test
    @DisplayName("R-09 A promoted replica's data survives restart, and a transaction rolled back on the primary does not reappear")
    void promotedReplicaSurvivesRestart() throws Exception {
        Path pDir = tempDir(), rDir = tempDir();
        KoraDB primary = new KoraDB(pDir);
        primary.execute("CREATE DATABASE blog");
        primary.execute("USE blog");
        ReplicationMaster master = new ReplicationMaster(ReplicationMasterConfig.onPort(0),
                ((DiskCatalog) primary.getCatalog()).getLogManager(), db -> Map.of());
        master.start();
        KoraDB replicaDb = new KoraDB(rDir);
        replicaDb.execute("CREATE DATABASE blog");
        replicaDb.execute("USE blog");
        ReplicationReplica replica = new ReplicationReplica(replicaDb,
                ReplicationReplicaConfig.of("r1", "localhost", master.getPort(), ReplicationMode.ASYNC));
        replica.start();

        primary.execute("CREATE TABLE posts (id INT, title VARCHAR)");
        for (int i = 0; i < 40; i++) primary.execute("INSERT INTO posts VALUES (" + i + ", 'p" + i + "')");
        primary.execute("UPDATE posts SET title = 'edited' WHERE id < 10");
        primary.execute("DELETE FROM posts WHERE id >= 35");
        primary.execute("BEGIN");
        primary.execute("INSERT INTO posts VALUES (9999, 'rolledback')");
        primary.execute("UPDATE posts SET title = 'rolledback' WHERE id = 20");
        primary.execute("ROLLBACK");
        primary.execute("INSERT INTO posts VALUES (100, 'last')");

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (replica.execute("SELECT * FROM posts WHERE id = 100").rows().size() == 1) break;
            } catch (Exception ignored) {}
            Thread.sleep(50);
        }
        for (String sneaky : List.of("SELECT 1; DELETE FROM posts", "TRUNCATE TABLE posts", "ALTER TABLE posts ADD COLUMN x INT")) {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> replica.execute(sneaky)))
                    .as(sneaky).isInstanceOf(dev.koradb.replication.model.ReadOnlyReplicaException.class);
        }
        master.close();
        primary.close();
        replica.promote();
        replica.execute("INSERT INTO posts VALUES (200, 'after promotion')");
        // An index created after promotion covers the rows that arrived through replication.
        replica.execute("CREATE INDEX posts_id ON posts (id)");
        assertThat(replica.execute("UPDATE posts SET title = 'keyed' WHERE id = 21").affectedRows()).isEqualTo(1);
        replica.close();
        replicaDb.close();

        KoraDB reopened = openDb(rDir);
        reopened.execute("USE blog");
        assertThat(count(reopened, "SELECT COUNT(*) FROM posts")).isEqualTo(35 + 1 + 1);
        assertThat(count(reopened, "SELECT COUNT(*) FROM posts WHERE title = 'rolledback'")).isZero();
        assertThat(count(reopened, "SELECT COUNT(*) FROM posts WHERE title = 'edited'")).isEqualTo(10);
        assertThat(count(reopened, "SELECT COUNT(*) FROM posts WHERE id = 21 AND title = 'keyed'")).isEqualTo(1);
    }
}
