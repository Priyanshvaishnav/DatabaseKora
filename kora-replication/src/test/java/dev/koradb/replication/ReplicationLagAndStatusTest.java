package dev.koradb.replication;

import dev.koradb.KoraDB;
import dev.koradb.catalog.DiskCatalog;
import dev.koradb.replication.master.ReplicationMaster;
import dev.koradb.replication.master.ReplicationMasterConfig;
import dev.koradb.replication.model.NodeRole;
import dev.koradb.replication.model.ReplicationMode;
import dev.koradb.replication.model.ReplicationStatus;
import dev.koradb.replication.replica.ReplicationReplica;
import dev.koradb.replication.replica.ReplicationReplicaConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicationLagAndStatusTest {

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
    @DisplayName("Primary and Replica expose detailed replication metrics, lag calculation, and health status")
    void testReplicationLagAndStatus() throws Exception {
        primaryDb = new KoraDB(primaryDir);
        primaryDb.execute("CREATE DATABASE telemetry");
        primaryDb.execute("USE telemetry");

        DiskCatalog primaryCatalog = (DiskCatalog) primaryDb.getCatalog();
        master = new ReplicationMaster(ReplicationMasterConfig.onPort(0), primaryCatalog.getLogManager(), db -> java.util.Map.of());
        master.start();

        replicaDb = new KoraDB(replicaDir);
        replicaDb.execute("CREATE DATABASE telemetry");
        replicaDb.execute("USE telemetry");

        replica = new ReplicationReplica(
                replicaDb,
                ReplicationReplicaConfig.of("replica-alpha", "localhost", master.getPort(), ReplicationMode.ASYNC)
        );
        replica.start();

        primaryDb.execute("CREATE TABLE sensor_readings (id INT, temp DOUBLE)");
        primaryDb.execute("INSERT INTO sensor_readings VALUES (1, 23.5)");

        // Wait for replication
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (replica.execute("SELECT * FROM sensor_readings").rows().size() == 1) {
                    break;
                }
            } catch (Exception ignored) {}
            TimeUnit.MILLISECONDS.sleep(50);
        }

        // 1. Inspect Primary Status
        ReplicationStatus primaryStatus = master.getStatus();
        assertThat(primaryStatus.role()).isEqualTo(NodeRole.PRIMARY);
        assertThat(primaryStatus.connectedReplicasCount()).isEqualTo(1);
        assertThat(primaryStatus.currentLsn()).isGreaterThan(16L);
        assertThat(primaryStatus.isHealthy()).isTrue();
        assertThat(primaryStatus.replicas()).hasSize(1);
        assertThat(primaryStatus.replicas().get(0).getReplicaId()).isEqualTo("replica-alpha");

        // 2. Inspect Replica Status
        ReplicationStatus replicaStatus = replica.getStatus();
        assertThat(replicaStatus.role()).isEqualTo(NodeRole.REPLICA);
        assertThat(replicaStatus.mode()).isEqualTo(ReplicationMode.ASYNC);
        assertThat(replicaStatus.isHealthy()).isTrue();
        assertThat(replicaStatus.appliedLsn()).isGreaterThan(16L);
        assertThat(replicaStatus.maxLagBytes()).isGreaterThanOrEqualTo(0L);
    }
}
