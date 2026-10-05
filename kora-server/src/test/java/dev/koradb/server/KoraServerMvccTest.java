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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KoraServerMvccTest {

    private KoraServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new KoraServer(KoraServerConfig.inMemory(0));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Should maintain repeatable read Snapshot Isolation across independent TCP client connections")
    void testNetworkSnapshotIsolation() throws IOException {
        int port = server.getPort();

        try (KoraClient admin = KoraClient.connect("127.0.0.1", port)) {
            admin.execute("CREATE DATABASE bank;");
            admin.execute("USE bank;");
            admin.execute("CREATE TABLE accounts (id INT, balance DOUBLE);");
            admin.execute("INSERT INTO accounts VALUES (1, 1000.0);");
        }

        try (KoraClient clientA = KoraClient.connect("127.0.0.1", port, "bank");
             KoraClient clientB = KoraClient.connect("127.0.0.1", port, "bank")) {

            // Client A starts transaction and reads balance
            clientA.execute("BEGIN;");
            ClientQueryResult readA1 = clientA.execute("SELECT balance FROM accounts WHERE id = 1;");
            assertThat(readA1.rows()).hasSize(1);
            assertThat(readA1.getValue(0, 0)).isEqualTo(1000.0);

            // Client B concurrently updates balance to 2500.0 and commits
            clientB.execute("BEGIN;");
            clientB.execute("UPDATE accounts SET balance = 2500.0 WHERE id = 1;");
            clientB.execute("COMMIT;");

            // Client B verifies updated balance
            ClientQueryResult readB = clientB.execute("SELECT balance FROM accounts WHERE id = 1;");
            assertThat(readB.getValue(0, 0)).isEqualTo(2500.0);

            // Client A reads again: MUST still see original snapshot 1000.0
            ClientQueryResult readA2 = clientA.execute("SELECT balance FROM accounts WHERE id = 1;");
            assertThat(readA2.getValue(0, 0)).isEqualTo(1000.0);

            // Client A finishes transaction
            clientA.execute("COMMIT;");

            // After commit, new statement sees 2500.0
            ClientQueryResult readA3 = clientA.execute("SELECT balance FROM accounts WHERE id = 1;");
            assertThat(readA3.getValue(0, 0)).isEqualTo(2500.0);
        }
    }

    @Test
    @DisplayName("Should detect write-write conflict and reject concurrent updater across network clients")
    void testNetworkWriteConflictDetection() throws IOException {
        int port = server.getPort();

        try (KoraClient admin = KoraClient.connect("127.0.0.1", port)) {
            admin.execute("CREATE DATABASE conflict_db;");
            admin.execute("USE conflict_db;");
            admin.execute("CREATE TABLE items (id INT, price DOUBLE);");
            admin.execute("INSERT INTO items VALUES (1, 50.0);");
        }

        try (KoraClient client1 = KoraClient.connect("127.0.0.1", port, "conflict_db");
             KoraClient client2 = KoraClient.connect("127.0.0.1", port, "conflict_db")) {

            // Client 1 begins and updates row 1
            client1.execute("BEGIN;");
            client1.execute("UPDATE items SET price = 60.0 WHERE id = 1;");

            // Client 2 attempts to concurrently update the same row
            client2.execute("BEGIN;");
            assertThatThrownBy(() -> client2.execute("UPDATE items SET price = 70.0 WHERE id = 1;"))
                    .isInstanceOf(KoraClientException.class)
                    .satisfies(e -> {
                        KoraClientException kce = (KoraClientException) e;
                        assertThat(kce.getErrorCode()).isEqualTo(ErrorMessage.ERR_CONFLICT);
                    });

            // Client 1 successfully commits
            client1.execute("COMMIT;");

            // Verification
            ClientQueryResult res = client1.execute("SELECT price FROM items WHERE id = 1;");
            assertThat(res.getValue(0, 0)).isEqualTo(60.0);
        }
    }
}
