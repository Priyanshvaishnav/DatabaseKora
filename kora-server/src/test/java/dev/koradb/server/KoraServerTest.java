package dev.koradb.server;

import dev.koradb.client.ClientQueryResult;
import dev.koradb.client.KoraClient;
import dev.koradb.client.KoraClientException;
import dev.koradb.protocol.ErrorMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KoraServerTest {

    private KoraServer server;

    @BeforeEach
    void setUp() throws IOException {
        // Ephemeral port 0 to prevent conflicts
        KoraServerConfig config = KoraServerConfig.inMemory(0);
        server = new KoraServer(config);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Should start server, accept client, ping, and shutdown gracefully")
    void testServerLifecycle() throws IOException {
        assertThat(server.isRunning()).isTrue();
        int port = server.getPort();
        assertThat(port).isGreaterThan(0);

        try (KoraClient client = KoraClient.connect("127.0.0.1", port)) {
            assertThat(client.ping()).isTrue();
            assertThat(client.getServerVersion()).contains("KoraDB");
            assertThat(client.getConnectionId()).isPositive();
        }

        server.stop();
        assertThat(server.isRunning()).isFalse();
    }

    @Test
    @DisplayName("Should execute DDL, DML, and SELECT queries over TCP socket")
    void testBasicQueryExecution() throws IOException {
        int port = server.getPort();
        try (KoraClient client = KoraClient.connect("127.0.0.1", port)) {
            client.execute("CREATE DATABASE app;");
            client.execute("USE app;");
            client.execute("CREATE TABLE users (id INT, name VARCHAR, balance DOUBLE, active BOOLEAN);");

            ClientQueryResult ins1 = client.execute("INSERT INTO users VALUES (1, 'Alice', 100.5, true);");
            assertThat(ins1.affectedRows()).isEqualTo(1);

            ClientQueryResult ins2 = client.execute("INSERT INTO users VALUES (2, 'Bob', 250.0, false);");
            assertThat(ins2.affectedRows()).isEqualTo(1);

            ClientQueryResult sel = client.execute("SELECT id, name, balance, active FROM users;");
            assertThat(sel.isQuery()).isTrue();
            assertThat(sel.columns()).containsExactly("id", "name", "balance", "active");
            assertThat(sel.rows()).hasSize(2);

            assertThat(sel.getValue(0, 0)).isEqualTo(1);
            assertThat(sel.getValue(0, 1)).isEqualTo("Alice");
            assertThat(sel.getValue(0, 2)).isEqualTo(100.5);
            assertThat(sel.getValue(0, 3)).isEqualTo(true);

            assertThat(sel.getValue(1, 0)).isEqualTo(2);
            assertThat(sel.getValue(1, 1)).isEqualTo("Bob");
            assertThat(sel.getValue(1, 2)).isEqualTo(250.0);
            assertThat(sel.getValue(1, 3)).isEqualTo(false);
        }
    }

    @Test
    @DisplayName("Should support explicit transactions with COMMIT and ROLLBACK over client connection")
    void testTransactionSupport() throws IOException {
        int port = server.getPort();
        try (KoraClient client = KoraClient.connect("127.0.0.1", port)) {
            client.execute("CREATE DATABASE tx_test;");
            client.execute("USE tx_test;");
            client.execute("CREATE TABLE items (id INT, name VARCHAR);");

            // 1. Rollback
            client.execute("BEGIN;");
            client.execute("INSERT INTO items VALUES (1, 'Item 1');");
            client.execute("ROLLBACK;");

            ClientQueryResult res1 = client.execute("SELECT * FROM items;");
            assertThat(res1.rows()).isEmpty();

            // 2. Commit
            client.execute("BEGIN;");
            client.execute("INSERT INTO items VALUES (2, 'Item 2');");
            client.execute("COMMIT;");

            ClientQueryResult res2 = client.execute("SELECT * FROM items;");
            assertThat(res2.rows()).hasSize(1);
            assertThat(res2.getValue(0, 0)).isEqualTo(2);
            assertThat(res2.getValue(0, 1)).isEqualTo("Item 2");
        }
    }

    @Test
    @DisplayName("Should return structured errors for syntax and schema violations")
    void testErrorHandling() throws IOException {
        int port = server.getPort();
        try (KoraClient client = KoraClient.connect("127.0.0.1", port)) {
            // Syntax error
            assertThatThrownBy(() -> client.execute("SELEKT * FROM dummy;"))
                    .isInstanceOf(KoraClientException.class)
                    .satisfies(e -> {
                        KoraClientException kce = (KoraClientException) e;
                        assertThat(kce.getErrorCode()).isEqualTo(ErrorMessage.ERR_PARSE);
                    });

            // Schema error
            assertThatThrownBy(() -> client.execute("SELECT * FROM nonexistent_table;"))
                    .isInstanceOf(KoraClientException.class)
                    .satisfies(e -> {
                        KoraClientException kce = (KoraClientException) e;
                        assertThat(kce.getErrorCode()).isEqualTo(ErrorMessage.ERR_SCHEMA);
                    });
        }
    }

    @Test
    @DisplayName("Should support concurrent client queries from multiple connections")
    void testMultiClientConcurrency() throws Exception {
        int port = server.getPort();

        // Setup table using admin client
        try (KoraClient admin = KoraClient.connect("127.0.0.1", port)) {
            admin.execute("CREATE DATABASE concurrent_db;");
            admin.execute("USE concurrent_db;");
            admin.execute("CREATE TABLE events (client_id INT, seq INT);");
        }

        int numClients = 8;
        int insertsPerClient = 10;
        ExecutorService pool = Executors.newFixedThreadPool(numClients);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < numClients; i++) {
            final int clientId = i;
            futures.add(pool.submit(() -> {
                startLatch.await();
                try (KoraClient client = KoraClient.connect("127.0.0.1", port, "concurrent_db")) {
                    for (int s = 0; s < insertsPerClient; s++) {
                        client.execute("INSERT INTO events VALUES (" + clientId + ", " + s + ");");
                    }
                }
                return null;
            }));
        }

        startLatch.countDown();
        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // Verify total row count
        try (KoraClient verifyClient = KoraClient.connect("127.0.0.1", port, "concurrent_db")) {
            ClientQueryResult res = verifyClient.execute("SELECT client_id, seq FROM events;");
            assertThat(res.rows()).hasSize(numClients * insertsPerClient);
        }
    }

    @Test
    @DisplayName("Should auto-rollback uncommitted transactions when client abruptly disconnects")
    void testAutoRollbackOnDisconnect() throws IOException {
        int port = server.getPort();

        try (KoraClient setup = KoraClient.connect("127.0.0.1", port)) {
            setup.execute("CREATE DATABASE rollback_test;");
            setup.execute("USE rollback_test;");
            setup.execute("CREATE TABLE pending (id INT);");
        }

        // Client 1 begins transaction and inserts, then closes without commit
        KoraClient client1 = KoraClient.connect("127.0.0.1", port, "rollback_test");
        client1.execute("BEGIN;");
        client1.execute("INSERT INTO pending VALUES (999);");
        client1.close(); // abrupt close

        // Client 2 connects and verifies pending table is empty
        try (KoraClient client2 = KoraClient.connect("127.0.0.1", port, "rollback_test")) {
            ClientQueryResult res = client2.execute("SELECT * FROM pending;");
            assertThat(res.rows()).isEmpty();
        }
    }
}
