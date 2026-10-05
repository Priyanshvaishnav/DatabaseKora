package dev.koradb.replication;

import dev.koradb.KoraDB;
import dev.koradb.execution.QueryResult;
import dev.koradb.replication.model.ReadOnlyReplicaException;
import dev.koradb.replication.model.ReplicationMode;
import dev.koradb.replication.replica.ReplicationReplica;
import dev.koradb.replication.replica.ReplicationReplicaConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadOnlyReplicaTest {

    @TempDir
    Path replicaDir;

    private KoraDB db;
    private ReplicationReplica replica;

    @BeforeEach
    void setUp() {
        db = new KoraDB(replicaDir);
        db.execute("CREATE DATABASE testdb");
        db.execute("USE testdb");
        db.execute("CREATE TABLE items (id INT, name VARCHAR, price DOUBLE)");
        db.execute("INSERT INTO items VALUES (1, 'Widget', 19.99)");

        // Initialize replica
        replica = new ReplicationReplica(
                db,
                ReplicationReplicaConfig.of("replica-ro", "localhost", 9999, ReplicationMode.ASYNC)
        );
    }

    @AfterEach
    void tearDown() {
        if (replica != null) replica.close();
    }

    @Test
    @DisplayName("Replica rejects all mutating DDL and DML operations")
    void testMutationsRejectedOnReplica() {
        assertThat(replica.isReadOnly()).isTrue();

        assertThatThrownBy(() -> replica.execute("INSERT INTO items VALUES (2, 'Gadget', 49.99)"))
                .isInstanceOf(ReadOnlyReplicaException.class)
                .hasMessageContaining("Cannot execute mutating statement on read-only replica");

        assertThatThrownBy(() -> replica.execute("UPDATE items SET price = 25.0 WHERE id = 1"))
                .isInstanceOf(ReadOnlyReplicaException.class)
                .hasMessageContaining("Cannot execute mutating statement on read-only replica");

        assertThatThrownBy(() -> replica.execute("DELETE FROM items WHERE id = 1"))
                .isInstanceOf(ReadOnlyReplicaException.class)
                .hasMessageContaining("Cannot execute mutating statement on read-only replica");

        assertThatThrownBy(() -> replica.execute("CREATE TABLE other (id INT)"))
                .isInstanceOf(ReadOnlyReplicaException.class)
                .hasMessageContaining("Cannot execute mutating statement on read-only replica");

        assertThatThrownBy(() -> replica.execute("DROP TABLE items"))
                .isInstanceOf(ReadOnlyReplicaException.class)
                .hasMessageContaining("Cannot execute mutating statement on read-only replica");
    }

    @Test
    @DisplayName("Replica permits SELECT queries with Snapshot Isolation")
    void testSelectQueriesAllowedOnReplica() {
        QueryResult res = replica.execute("SELECT * FROM items WHERE id = 1");
        assertThat(res.rows()).hasSize(1);
        assertThat(res.rows().get(0).get("name")).isEqualTo("Widget");
        assertThat(res.rows().get(0).get("price")).isEqualTo(19.99);

        QueryResult explainRes = replica.execute("EXPLAIN SELECT * FROM items");
        assertThat(explainRes.rows()).isNotEmpty();
    }
}
