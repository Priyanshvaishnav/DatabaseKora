package dev.koradb.e2e;

import dev.koradb.client.KoraClient;
import dev.koradb.client.KoraClientException;
import dev.koradb.replication.model.NodeRole;
import dev.koradb.server.Credentials;
import dev.koradb.server.ServerBootstrapConfig;
import dev.koradb.server.ServerInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** JDBC, HTTP API, metrics and server/deployment rows of the end-to-end report that were FAIL or WARN. */
class ServerAndApiRegressionTest {

    private final List<ServerInstance> nodes = new ArrayList<>();
    private final List<Path> dirs = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (int i = nodes.size() - 1; i >= 0; i--) nodes.get(i).stop();
        dirs.forEach(E2e::deleteTree);
    }

    private ServerInstance node(NodeRole role, boolean metrics) throws Exception {
        return node(role, metrics, 0);
    }

    private ServerInstance node(NodeRole role, boolean metrics, int primaryReplicationPort) throws Exception {
        Path dir = Files.createTempDirectory("kora-e2e-srv");
        dirs.add(dir);
        ServerInstance n = Servers.start(role, dir, metrics, primaryReplicationPort);
        nodes.add(n);
        return n;
    }

    // ---------------------------------------------------------------- JDBC

    @Test
    @DisplayName("F-JDBC-02/03/04/05/16 PreparedStatement: negative int, setNull + wasNull, setTimestamp, large double")
    void preparedStatementParameters() throws Exception {
        ServerInstance n = node(NodeRole.STANDALONE, false);
        n.getDb().execute("CREATE DATABASE jdbc_db");
        try (Connection c = Servers.jdbc(n, "jdbc_db"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE p (i INT, v VARCHAR, t TIMESTAMP, d DOUBLE)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO p VALUES (?, ?, ?, ?)")) {
                ps.setInt(1, -42);
                ps.setNull(2, Types.VARCHAR);
                ps.setTimestamp(3, Timestamp.valueOf("2024-05-06 07:08:09.123"));
                ps.setDouble(4, 1e10);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            try (ResultSet rs = s.executeQuery("SELECT i, v, t, d FROM p WHERE i = -42")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("i")).isEqualTo(-42);
                assertThat(rs.getString("v")).isNull();
                assertThat(rs.wasNull()).isTrue();
                assertThat(rs.getTimestamp("t").toLocalDateTime().toString()).startsWith("2024-05-06T07:08:09");
                assertThat(rs.getDouble("d")).isEqualTo(1e10);
            }
        }
    }

    @Test
    @DisplayName("JDBC setTimestamp with zero seconds round-trips, and WHERE accepts every form INSERT accepts")
    void timestampWithZeroSeconds() throws Exception {
        ServerInstance n = node(NodeRole.STANDALONE, false);
        n.getDb().execute("CREATE DATABASE jdbc_db");
        try (Connection c = Servers.jdbc(n, "jdbc_db"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE ev (id INT, at TIMESTAMP)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO ev VALUES (?, ?)")) {
                ps.setInt(1, 1);
                ps.setTimestamp(2, Timestamp.valueOf("2020-06-11 07:53:00"));   // LocalDateTime.toString() = "2020-06-11T07:53"
                ps.executeUpdate();
                ps.setInt(1, 2);
                ps.setObject(2, java.time.LocalDateTime.of(2021, 1, 1, 0, 0));
                ps.executeUpdate();
            }
            try (ResultSet rs = s.executeQuery("SELECT at FROM ev WHERE id = 1")) {
                rs.next();
                assertThat(rs.getTimestamp(1)).isEqualTo(Timestamp.valueOf("2020-06-11 07:53:00"));
            }
            for (String lit : List.of("2020-06-11 07:53:00", "2020-06-11T07:53", "2020-06-11 07:53", "2020-06-11T07:53:00.000")) {
                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM ev WHERE at = '" + lit + "'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as(lit).isEqualTo(1);
                }
            }
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM ev WHERE at >= '2021-01-01'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("F-JDBC-08 executeBatch inserts all rows and returns per-statement counts")
    void executeBatch() throws Exception {
        ServerInstance n = node(NodeRole.STANDALONE, false);
        n.getDb().execute("CREATE DATABASE jdbc_db");
        try (Connection c = Servers.jdbc(n, "jdbc_db"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE b (i INT)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO b VALUES (?)")) {
                for (int i = 0; i < 25; i++) {
                    ps.setInt(1, i);
                    ps.addBatch();
                }
                int[] counts = ps.executeBatch();
                assertThat(counts).hasSize(25).containsOnly(1);
            }
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM b")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(25);
            }
        }
    }

    // ---------------------------------------------------------------- HTTP API

    @Test
    @DisplayName("H-10 A query against a misspelled database does not create it")
    void misspelledDatabaseNotCreated() throws Exception {
        ServerInstance n = node(NodeRole.STANDALONE, false);
        n.getDb().execute("CREATE DATABASE diff");
        int port = n.getHttpServer().getPort();
        HttpResponse<String> r = Servers.post(port, "/v1/query", Servers.query("SELECT * FROM emp", "difff_typo"));
        assertThat(r.statusCode()).isBetween(400, 499);
        assertThat(Servers.get(port, "/v1/databases").body()).doesNotContain("difff_typo");
    }

    @Test
    @DisplayName("H-13/H-14 8 parallel HTTP clients on 2 databases get correct answers and the server stays healthy")
    void parallelHttpClients() throws Exception {
        ServerInstance n = node(NodeRole.STANDALONE, false);
        int port = n.getHttpServer().getPort();
        for (String d : List.of("http_a", "http_b")) {
            n.getDb().execute("CREATE DATABASE " + d);
            assertThat(Servers.post(port, "/v1/query", Servers.query("CREATE TABLE t (id INT, who VARCHAR)", d)).statusCode()).isEqualTo(200);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int k = 0; k < 8; k++) {
                String d = k % 2 == 0 ? "http_a" : "http_b";
                int worker = k;
                fs.add(pool.submit(() -> {
                    for (int i = 0; i < 25; i++) {
                        var ins = Servers.post(port, "/v1/query", Servers.query("INSERT INTO t VALUES (" + (worker * 100 + i) + ", '" + d + "')", d));
                        assertThat(ins.statusCode()).as(ins.body()).isEqualTo(200);
                        var sel = Servers.post(port, "/v1/query", Servers.query("SELECT COUNT(*) FROM t WHERE who <> '" + d + "'", d));
                        assertThat(sel.body()).as("foreign rows in " + d).contains("[[0]]");
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        for (String d : List.of("http_a", "http_b")) {
            assertThat(Servers.post(port, "/v1/query", Servers.query("SELECT COUNT(*) FROM t", d)).body()).contains("[[100]]");
        }
        assertThat(Servers.get(port, "/v1/health").body()).contains("UP");
    }

    // ---------------------------------------------------------------- metrics

    @Test
    @DisplayName("M-03/M-08 Query and transaction counters increase after JDBC and HTTP traffic")
    void metricsCountersMove() throws Exception {
        ServerInstance n = node(NodeRole.STANDALONE, true);
        int metricsPort = n.getMetricsServer().getPort();
        double queriesBefore = sum(Servers.get(metricsPort, "/metrics").body(), "koradb_queries_total");
        double commitsBefore = sum(Servers.get(metricsPort, "/metrics").body(), "koradb_transactions_committed_total");

        n.getDb().execute("CREATE DATABASE met");
        try (Connection c = Servers.jdbc(n, "met"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE m (i INT)");
            for (int i = 0; i < 10; i++) s.executeUpdate("INSERT INTO m VALUES (" + i + ")");
            for (int i = 0; i < 10; i++) s.executeQuery("SELECT * FROM m").close();
            c.setAutoCommit(false);
            s.executeUpdate("UPDATE m SET i = i + 1");
            c.commit();
        }
        Servers.post(n.getHttpServer().getPort(), "/v1/query", Servers.query("SELECT COUNT(*) FROM m", "met"));

        String after = Servers.get(metricsPort, "/metrics").body();
        assertThat(sum(after, "koradb_queries_total") - queriesBefore).isGreaterThanOrEqualTo(21);
        assertThat(sum(after, "koradb_transactions_committed_total") - commitsBefore).isGreaterThanOrEqualTo(11);
    }

    private static double sum(String exposition, String family) {
        Matcher m = Pattern.compile("(?m)^" + family + "(\\{[^}]*})?\\s+([0-9.eE+-]+)$").matcher(exposition);
        double total = 0;
        while (m.find()) total += Double.parseDouble(m.group(2));
        return total;
    }

    // ---------------------------------------------------------------- S-08 authentication

    private ServerInstance securedNode(NodeRole role, int primaryReplicationPort) throws Exception {
        Path dir = Files.createTempDirectory("kora-e2e-auth");
        dirs.add(dir);
        ServerInstance n = Servers.start(role, dir, false, primaryReplicationPort, new Credentials("admin", "s3cret"));
        nodes.add(n);
        return n;
    }

    @Test
    @DisplayName("S-08 With a password set, the SQL port (TCP and JDBC) rejects missing or wrong credentials")
    void sqlPortRequiresPassword() throws Exception {
        ServerInstance n = securedNode(NodeRole.STANDALONE, 0);
        int port = n.getServer().getPort();
        String url = "jdbc:koradb://127.0.0.1:" + port + "/secure";

        for (String[] bad : new String[][]{{null, null}, {"admin", "wrong"}, {"intruder", "s3cret"}}) {
            SQLException e = null;
            try {
                (bad[0] == null ? DriverManager.getConnection(url) : DriverManager.getConnection(url, bad[0], bad[1])).close();
            } catch (SQLException ex) {
                e = ex;
            }
            assertThat((Throwable) e).as(java.util.Arrays.toString(bad)).isNotNull();
            assertThat(e.getSQLState()).isEqualTo("28000");
        }
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> KoraClient.connect("127.0.0.1", port, "", "admin", "nope")))
                .isInstanceOf(KoraClientException.class).hasMessageContaining("Authentication failed");

        try (Connection c = DriverManager.getConnection(url, "admin", "s3cret"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (i INT)");
            assertThat(s.executeUpdate("INSERT INTO t VALUES (1)")).isEqualTo(1);
        }
        try (Connection c = DriverManager.getConnection(url + "?user=admin&password=s3cret")) {
            assertThat(c.isValid(2)).isTrue();
        }
    }

    @Test
    @DisplayName("S-08 With a password set, the HTTP API needs Basic or Bearer auth (health stays open)")
    void httpRequiresPassword() throws Exception {
        ServerInstance n = securedNode(NodeRole.STANDALONE, 0);
        n.getDb().execute("CREATE DATABASE secure");
        int port = n.getHttpServer().getPort();
        String body = Servers.query("SELECT 1", "secure");
        HttpResponse<String> anon = Servers.post(port, "/v1/query", body, null);
        assertThat(anon.statusCode()).isEqualTo(401);
        assertThat(anon.headers().firstValue("WWW-Authenticate")).isPresent();
        assertThat(Servers.post(port, "/v1/query", body, "Bearer wrong").statusCode()).isEqualTo(401);
        assertThat(Servers.get(port, "/v1/databases").statusCode()).isEqualTo(401);

        String basic = "Basic " + java.util.Base64.getEncoder().encodeToString("admin:s3cret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(Servers.post(port, "/v1/query", body, basic).statusCode()).isEqualTo(200);
        assertThat(Servers.post(port, "/v1/query", body, "Bearer s3cret").statusCode()).isEqualTo(200);
        assertThat(Servers.get(port, "/v1/health").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("S-08 With a password set, only replicas that present it receive the snapshot and WAL stream")
    void replicationRequiresPassword() throws Exception {
        ServerInstance primary = securedNode(NodeRole.PRIMARY, 0);
        ServerInstance replica = securedNode(NodeRole.REPLICA, primary.getMaster().getPort());
        try (Connection c = DriverManager.getConnection("jdbc:koradb://127.0.0.1:" + primary.getServer().getPort() + "/default",
                "admin", "s3cret"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE r (i INT)");
            s.executeUpdate("INSERT INTO r VALUES (1)");
        }

        // A replica without the password is refused at the handshake.
        Path intruderDir = Files.createTempDirectory("kora-e2e-intruder");
        dirs.add(intruderDir);
        try (dev.koradb.KoraDB intruderDb = new dev.koradb.KoraDB(intruderDir)) {
            var cat = (dev.koradb.catalog.DiskCatalog) intruderDb.getCatalog();
            cat.createDatabase("default");
            cat.useDatabase("default");
            try (var intruder = new dev.koradb.replication.replica.ReplicationReplica(intruderDb,
                    dev.koradb.replication.replica.ReplicationReplicaConfig.of("intruder", "127.0.0.1", primary.getMaster().getPort()))) {
                intruder.start();
                Thread.sleep(1500);
                assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> intruderDb.execute("SELECT * FROM r")))
                        .as("intruder must not have received the table").isNotNull();
            } catch (Exception startRefused) {
                // start() may itself fail on the rejected handshake: that is fine too
            }
        }

        // The real replica (same password) is served.
        long deadline = System.currentTimeMillis() + 15_000;
        long seen = -1;
        try (Connection c = DriverManager.getConnection("jdbc:koradb://127.0.0.1:" + replica.getServer().getPort() + "/default",
                "admin", "s3cret"); Statement s = c.createStatement()) {
            while (System.currentTimeMillis() < deadline && seen != 1) {
                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM r")) {
                    rs.next();
                    seen = rs.getLong(1);
                } catch (SQLException notYet) {
                    Thread.sleep(100);
                }
            }
        }
        assertThat(seen).isEqualTo(1);
    }

    @Test
    @DisplayName("S-08 Credentials come from flags, env or a password file and are never printed")
    void credentialsConfiguration() throws Exception {
        Path secret = Files.createTempFile("kora-secret", ".txt");
        dirs.add(secret);
        Files.writeString(secret, "from-file\n");
        ServerBootstrapConfig cfg = ServerBootstrapConfig.fromEnvAndArgs(new String[]{"--user", "admin", "--password-file", secret.toString()});
        assertThat(cfg.credentials().user()).isEqualTo("admin");
        assertThat(cfg.credentials().accepts("admin", "from-file")).isTrue();
        assertThat(cfg.toString()).doesNotContain("from-file");
        assertThat(ServerBootstrapConfig.fromEnvAndArgs(new String[]{"--password", "hunter2"}).toString()).doesNotContain("hunter2");
        assertThat(ServerBootstrapConfig.fromEnvAndArgs(new String[]{"--host", "0.0.0.0"}).bindsBeyondLoopback()).isTrue();
        assertThat(ServerBootstrapConfig.fromEnvAndArgs(new String[]{"--host", "127.0.0.1"}).bindsBeyondLoopback()).isFalse();
    }

    // ---------------------------------------------------------------- server & deployment

    @Test
    @DisplayName("S-01/S-02 Server starts with a data dir in the default PRIMARY role and serves queries")
    void primaryWithDataDirStarts() throws Exception {
        ServerInstance n = node(NodeRole.PRIMARY, false);
        try (Connection c = Servers.jdbc(n, "default"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE ok (i INT)");
            assertThat(s.executeUpdate("INSERT INTO ok VALUES (1)")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("S-04 Replica role via the server: rows written on the PRIMARY are served read-only by the REPLICA")
    void replicaRoleThroughServer() throws Exception {
        ServerInstance primary = node(NodeRole.PRIMARY, false);
        ServerInstance replica = node(NodeRole.REPLICA, false, primary.getMaster().getPort());
        try (Connection c = Servers.jdbc(primary, "default"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE r (i INT)");
            for (int i = 0; i < 5; i++) s.executeUpdate("INSERT INTO r VALUES (" + i + ")");
        }
        long deadline = System.currentTimeMillis() + 15_000;
        long seen = -1;
        try (Connection c = Servers.jdbc(replica, "default"); Statement s = c.createStatement()) {
            while (System.currentTimeMillis() < deadline) {
                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM r")) {
                    rs.next();
                    seen = rs.getLong(1);
                    if (seen == 5) break;
                } catch (SQLException notYet) {
                    // table not replicated yet
                }
                Thread.sleep(100);
            }
            assertThat(seen).isEqualTo(5);
            SQLException rejected = null;
            try {
                s.executeUpdate("INSERT INTO r VALUES (99)");
            } catch (SQLException e) {
                rejected = e;
            }
            assertThat((Throwable) rejected).as("replica must reject writes").isNotNull();

            // Every statement of a multi-statement string is checked, and TRUNCATE / ALTER count as writes.
            for (String sneaky : List.of("SELECT 1; DELETE FROM r", "TRUNCATE TABLE r", "ALTER TABLE r ADD COLUMN x INT",
                    "EXPLAIN ANALYZE DELETE FROM r")) {
                SQLException e = null;
                try {
                    s.execute(sneaky);
                } catch (SQLException ex) {
                    e = ex;
                }
                assertThat((Throwable) e).as(sneaky).isNotNull();
            }
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM r")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(5);
            }
        }

        int http = replica.getHttpServer().getPort();
        assertThat(Servers.post(http, "/v1/query", Servers.query("SELECT 1; DELETE FROM r", "default")).statusCode()).isEqualTo(403);
        assertThat(Servers.post(http, "/v1/query?create=true", Servers.query("SELECT 1", "made_on_replica")).statusCode()).isEqualTo(404);
        assertThat(Servers.get(http, "/v1/databases").body()).doesNotContain("made_on_replica");
        try (Connection c = Servers.jdbc(primary, "default"); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM r")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(5);
        }
    }
}
