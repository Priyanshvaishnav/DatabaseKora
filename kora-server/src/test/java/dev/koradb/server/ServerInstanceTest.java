package dev.koradb.server;

import dev.koradb.client.ClientQueryResult;
import dev.koradb.client.KoraClient;
import dev.koradb.client.KoraClientException;
import dev.koradb.replication.model.NodeRole;
import dev.koradb.replication.model.ReplicationMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerInstanceTest {

    private ServerInstance instance;

    @AfterEach
    void tearDown() {
        if (instance != null) {
            instance.stop();
        }
    }

    @Test
    @DisplayName("Should bootstrap standalone node, execute queries, and expose HTTP healthcheck")
    void testStandaloneLifecycle(@TempDir Path tempDir) throws Exception {
        ServerBootstrapConfig config = new ServerBootstrapConfig(
                "127.0.0.1",
                0, // ephemeral SQL port
                tempDir,
                NodeRole.STANDALONE,
                0,
                ReplicationMode.ASYNC,
                "localhost",
                0,
                "test-node",
                true,
                0, // ephemeral metrics port
                true,
                0  // ephemeral HTTP port
        );

        instance = new ServerInstance(config);
        instance.start();

        assertThat(instance.isRunning()).isTrue();
        int sqlPort = instance.getServer().getPort();
        int metricsPort = instance.getMetricsServer().getPort();
        int httpPort = instance.getHttpServer().getPort();
        assertThat(sqlPort).isGreaterThan(0);
        assertThat(metricsPort).isGreaterThan(0);
        assertThat(httpPort).isGreaterThan(0);

        // 1. Test SQL client interaction
        try (KoraClient client = KoraClient.connect("127.0.0.1", sqlPort)) {
            assertThat(client.ping()).isTrue();
            client.execute("CREATE DATABASE app");
            client.execute("USE app");
            client.execute("CREATE TABLE users (id INT, name VARCHAR)");
            client.execute("INSERT INTO users VALUES (1, 'Alice')");

            ClientQueryResult res = client.execute("SELECT * FROM users");
            assertThat(res.rows()).hasSize(1);
            assertThat(res.rows().get(0).get(1)).isEqualTo("Alice");
        }

        // 2. Test HTTP REST query endpoint
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest httpQueryReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + httpPort + "/v1/query?database=app"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"sql\": \"SELECT * FROM users\"}"))
                .build();
        HttpResponse<String> httpQueryRes = httpClient.send(httpQueryReq, HttpResponse.BodyHandlers.ofString());
        assertThat(httpQueryRes.statusCode()).isEqualTo(200);
        assertThat(httpQueryRes.body()).contains("\"Alice\"");

        // 3. Test Prometheus /health endpoint
        HttpRequest healthReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + metricsPort + "/health"))
                .GET()
                .build();
        HttpResponse<String> healthRes = httpClient.send(healthReq, HttpResponse.BodyHandlers.ofString());
        assertThat(healthRes.statusCode()).isEqualTo(200);
        assertThat(healthRes.body()).contains("\"status\":\"UP\"");

        // 3. Test Prometheus /metrics endpoint
        HttpRequest metricsReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + metricsPort + "/metrics"))
                .GET()
                .build();
        HttpResponse<String> metricsRes = httpClient.send(metricsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(metricsRes.statusCode()).isEqualTo(200);
        assertThat(metricsRes.body()).contains("koradb_");

        instance.stop();
        assertThat(instance.isRunning()).isFalse();
    }

    @Test
    @DisplayName("Read-only server configuration should block mutating DDL and DML statements")
    void testReadOnlyServerRejection() throws IOException {
        KoraServerConfig config = KoraServerConfig.readOnly("127.0.0.1", 0, null);
        KoraServer server = new KoraServer(config);
        server.start();

        try {
            int port = server.getPort();
            try (KoraClient client = KoraClient.connect("127.0.0.1", port)) {
                // Reads work
                ClientQueryResult dbs = client.execute("SHOW DATABASES;");
                assertThat(dbs).isNotNull();

                // Mutations fail
                assertThatThrownBy(() -> client.execute("CREATE DATABASE blocked;"))
                        .isInstanceOf(KoraClientException.class)
                        .hasMessageContaining("Cannot execute mutating statement on read-only replica");

                assertThatThrownBy(() -> client.execute("INSERT INTO blocked VALUES (1);"))
                        .isInstanceOf(KoraClientException.class)
                        .hasMessageContaining("Cannot execute mutating statement on read-only replica");
            }
        } finally {
            server.stop();
        }
    }
}
