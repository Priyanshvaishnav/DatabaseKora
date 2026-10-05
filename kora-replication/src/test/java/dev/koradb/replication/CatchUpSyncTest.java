package dev.koradb.replication;

import dev.koradb.KoraDB;
import dev.koradb.catalog.DiskCatalog;
import dev.koradb.execution.QueryResult;
import dev.koradb.replication.master.ReplicationMaster;
import dev.koradb.replication.master.ReplicationMasterConfig;
import dev.koradb.replication.model.ReplicationMode;
import dev.koradb.replication.replica.ReplicationReplica;
import dev.koradb.replication.replica.ReplicationReplicaConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CatchUpSyncTest {

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
    @DisplayName("Replica catches up historical WAL records on cold start and after reconnecting")
    void testCatchUpAfterLateJoinAndDisconnect() throws Exception {
        // 1. Primary starts and commits initial batch of rows before replica joins
        primaryDb = new KoraDB(primaryDir);
        primaryDb.execute("CREATE DATABASE ledger");
        primaryDb.execute("USE ledger");

        DiskCatalog primaryCatalog = (DiskCatalog) primaryDb.getCatalog();
        master = new ReplicationMaster(ReplicationMasterConfig.onPort(0), primaryCatalog.getLogManager(), db -> java.util.Map.of());
        master.start();

        primaryDb.execute("CREATE TABLE transactions (id INT, amount DOUBLE)");
        for (int i = 1; i <= 5; i++) {
            primaryDb.execute("INSERT INTO transactions VALUES (" + i + ", " + (i * 10.0) + ")");
        }

        // 2. Replica connects for the first time
        replicaDb = new KoraDB(replicaDir);
        replicaDb.execute("CREATE DATABASE ledger");
        replicaDb.execute("USE ledger");

        replica = new ReplicationReplica(
                replicaDb,
                ReplicationReplicaConfig.of("replica-catchup", "localhost", master.getPort(), ReplicationMode.ASYNC)
        );
        replica.start();

        // Wait for initial catch-up of 5 rows
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (replica.execute("SELECT * FROM transactions").rows().size() == 5) {
                    break;
                }
            } catch (Exception ignored) {}
            TimeUnit.MILLISECONDS.sleep(50);
        }

        QueryResult res1 = replica.execute("SELECT * FROM transactions");
        assertThat(res1.rows()).hasSize(5);

        // 3. Disconnect replica
        replica.close();

        // 4. Primary writes 5 more rows while replica is offline
        for (int i = 6; i <= 10; i++) {
            primaryDb.execute("INSERT INTO transactions VALUES (" + i + ", " + (i * 10.0) + ")");
        }

        // 5. Replica reconnects with its current applied LSN
        replica = new ReplicationReplica(
                replicaDb,
                ReplicationReplicaConfig.of("replica-catchup", "localhost", master.getPort(), ReplicationMode.ASYNC)
        );
        replica.start();

        // Wait for second catch-up to reach 10 rows
        deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (replica.execute("SELECT * FROM transactions").rows().size() == 10) {
                    break;
                }
            } catch (Exception ignored) {}
            TimeUnit.MILLISECONDS.sleep(50);
        }

        QueryResult res2 = replica.execute("SELECT * FROM transactions");
        assertThat(res2.rows()).hasSize(10);
    }
}
