package dev.koradb.replication;

import dev.koradb.KoraDB;
import dev.koradb.catalog.DiskCatalog;
import dev.koradb.execution.QueryResult;
import dev.koradb.replication.master.ReplicationMaster;
import dev.koradb.replication.master.ReplicationMasterConfig;
import dev.koradb.replication.model.ReplicationMode;
import dev.koradb.replication.model.ReplicationTimeoutException;
import dev.koradb.replication.replica.ReplicationReplica;
import dev.koradb.replication.replica.ReplicationReplicaConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncReplicationTest {

    @TempDir
    Path primaryDir;

    @TempDir
    Path replicaDir;

    private KoraDB primaryDb;
    private KoraDB replicaDb;
    private ReplicationMaster master;
    private ReplicationReplica replica;

    @AfterEach
    void tearDown() {
        if (replica != null) replica.close();
        if (master != null) master.close();
        if (replicaDb != null) replicaDb.close();
        if (primaryDb != null) primaryDb.close();
    }

    @Test
    @DisplayName("Synchronous commit returns only after replica acks and flushes WAL")
    void testSyncCommitGuaranteesReplicaDurability() throws Exception {
        primaryDb = new KoraDB(primaryDir);
        primaryDb.execute("CREATE DATABASE finance");
        primaryDb.execute("USE finance");

        DiskCatalog primaryCatalog = (DiskCatalog) primaryDb.getCatalog();
        ReplicationMasterConfig masterConfig = new ReplicationMasterConfig(
                "0.0.0.0", 0, "primary-1", ReplicationMode.SYNC, 500L, 5000L, 100);
        master = new ReplicationMaster(masterConfig, primaryCatalog.getLogManager(), dbName -> java.util.Map.of());
        master.start();
        master.attachTo(primaryDb);

        replicaDb = new KoraDB(replicaDir);
        replicaDb.execute("CREATE DATABASE finance");
        replicaDb.execute("USE finance");

        replica = new ReplicationReplica(
                replicaDb,
                ReplicationReplicaConfig.of("replica-1", "localhost", master.getPort(), ReplicationMode.SYNC)
        );
        replica.start();

        // Allow connection handshake to complete
        Thread.sleep(150);

        primaryDb.execute("CREATE TABLE users (id INT, name VARCHAR)");
        primaryDb.execute("INSERT INTO users VALUES (1, 'Alice')");

        // When commit returns in SYNC mode, replica has already flushed the commit LSN
        QueryResult res = replica.execute("SELECT * FROM users WHERE id = 1");
        assertThat(res.rows()).hasSize(1);
        assertThat(res.rows().get(0).get("name")).isEqualTo("Alice");
    }

    @Test
    @DisplayName("Synchronous commit throws ReplicationTimeoutException when replica is missing")
    void testSyncCommitTimesOutWhenNoReplicaConnected() throws Exception {
        primaryDb = new KoraDB(primaryDir);
        primaryDb.execute("CREATE DATABASE finance");
        primaryDb.execute("USE finance");

        DiskCatalog primaryCatalog = (DiskCatalog) primaryDb.getCatalog();
        // Configure master with short 300ms timeout
        ReplicationMasterConfig masterConfig = new ReplicationMasterConfig(
                "0.0.0.0", 0, "primary-1", ReplicationMode.SYNC, 500L, 300L, 100);
        master = new ReplicationMaster(masterConfig, primaryCatalog.getLogManager(), dbName -> java.util.Map.of());
        master.start();

        primaryDb.execute("CREATE TABLE users (id INT, name VARCHAR)");
        master.attachTo(primaryDb);

        // No replica connected -> sync commit must time out
        assertThatThrownBy(() -> primaryDb.execute("INSERT INTO users VALUES (99, 'Ghost')"))
                .isInstanceOf(ReplicationTimeoutException.class)
                .hasMessageContaining("Timed out after 300 ms waiting for synchronous replica ACK");
    }
}
