package dev.koradb.e2e;

import dev.koradb.replication.model.NodeRole;
import dev.koradb.replication.model.ReplicationMode;
import dev.koradb.server.Credentials;
import dev.koradb.server.ServerBootstrapConfig;
import dev.koradb.server.ServerInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;

/** Starts real server nodes on ephemeral ports for the end-to-end regression tests. */
final class Servers {

    private Servers() {}

    static ServerInstance start(NodeRole role, Path dataDir, boolean metrics) throws Exception {
        return start(role, dataDir, metrics, 0);
    }

    static ServerInstance start(NodeRole role, Path dataDir, boolean metrics, int primaryReplicationPort) throws Exception {
        return start(role, dataDir, metrics, primaryReplicationPort, Credentials.NONE);
    }

    static ServerInstance start(NodeRole role, Path dataDir, boolean metrics, int primaryReplicationPort,
                                Credentials credentials) throws Exception {
        ServerBootstrapConfig cfg = new ServerBootstrapConfig(
                "127.0.0.1", 0, dataDir, role,
                0, ReplicationMode.ASYNC, "127.0.0.1", primaryReplicationPort, "replica-e2e",
                metrics, 0, true, 0, credentials);
        ServerInstance node = new ServerInstance(cfg);
        node.start();
        return node;
    }

    static Connection jdbc(ServerInstance node, String database) throws SQLException {
        return DriverManager.getConnection("jdbc:koradb://127.0.0.1:" + node.getServer().getPort() + "/" + database);
    }

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static HttpResponse<String> post(int port, String pathAndQuery, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(int port, String path, String body, String authorization) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) b.header("Authorization", authorization);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static String query(String sql, String database) {
        return "{\"sql\":\"" + sql.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"database\":\"" + database + "\"}";
    }
}
