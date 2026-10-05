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

class AsyncReplicationTest {

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
    @DisplayName("Primary writes propagate asynchronously to Replica and are readable via SELECT")
    void testAsyncReplication() throws Exception {
        // 1. Initialize Primary
        primaryDb = new KoraDB(primaryDir);
        primaryDb.execute("CREATE DATABASE store");
        primaryDb.execute("USE store");

        DiskCatalog primaryCatalog = (DiskCatalog) primaryDb.getCatalog();
        master = new ReplicationMaster(
                ReplicationMasterConfig.onPort(0),
                primaryCatalog.getLogManager(),
                dbName -> java.util.Map.of()
        );
        master.start();

        // 2. Initialize Replica
        replicaDb = new KoraDB(replicaDir);
        replicaDb.execute("CREATE DATABASE store");
        replicaDb.execute("USE store");

        replica = new ReplicationReplica(
                replicaDb,
                ReplicationReplicaConfig.of("replica-1", "localhost", master.getPort(), ReplicationMode.ASYNC)
        );
        replica.start();

        // 3. Write on Primary
        primaryDb.execute("CREATE TABLE products (id INT, name VARCHAR, price DOUBLE)");
        primaryDb.execute("INSERT INTO products VALUES (1, 'Laptop', 1299.99)");
        primaryDb.execute("INSERT INTO products VALUES (2, 'Mouse', 29.50)");

        // 4. Wait for replication to catch up (polling with timeout)
        long deadline = System.currentTimeMillis() + 5000L;
        boolean caughtUp = false;
        while (System.currentTimeMillis() < deadline) {
            try {
                QueryResult res = replica.execute("SELECT * FROM products");
                if (res.rows().size() == 2) {
                    caughtUp = true;
                    break;
                }
            } catch (Exception ignored) {
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }

        assertThat(caughtUp)
                .as("Replica should catch up with primary rows within timeout")
                .isTrue();

        QueryResult replicaRes = replica.execute("SELECT name, price FROM products WHERE price > 50.0");
        assertThat(replicaRes.rows()).hasSize(1);
        assertThat(replicaRes.rows().get(0).get("name")).isEqualTo("Laptop");
        assertThat(replicaRes.rows().get(0).get("price")).isEqualTo(1299.99);
    }
}
