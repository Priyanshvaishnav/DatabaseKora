package dev.koradb.replication;

import dev.koradb.KoraDB;
import dev.koradb.catalog.DiskCatalog;
import dev.koradb.execution.QueryResult;
import dev.koradb.replication.master.ReplicationMaster;
import dev.koradb.replication.master.ReplicationMasterConfig;
import dev.koradb.replication.model.NodeRole;
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

class FailoverAndPromotionTest {

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
    @DisplayName("Replica can be promoted to Primary upon failover and accept read-write transactions")
    void testFailoverAndPromotion() throws Exception {
        // 1. Primary setup
        primaryDb = new KoraDB(primaryDir);
        primaryDb.execute("CREATE DATABASE blog");
        primaryDb.execute("USE blog");

        DiskCatalog primaryCatalog = (DiskCatalog) primaryDb.getCatalog();
        master = new ReplicationMaster(ReplicationMasterConfig.onPort(0), primaryCatalog.getLogManager(), db -> java.util.Map.of());
        master.start();

        // 2. Replica setup
        replicaDb = new KoraDB(replicaDir);
        replicaDb.execute("CREATE DATABASE blog");
        replicaDb.execute("USE blog");

        replica = new ReplicationReplica(
                replicaDb,
                ReplicationReplicaConfig.of("replica-node", "localhost", master.getPort(), ReplicationMode.ASYNC)
        );
        replica.start();

        // 3. Write on Primary
        primaryDb.execute("CREATE TABLE posts (id INT, title VARCHAR)");
        primaryDb.execute("INSERT INTO posts VALUES (1, 'Hello World')");

        // Wait for replication catchup
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (replica.execute("SELECT * FROM posts").rows().size() == 1) {
                    break;
                }
            } catch (Exception ignored) {}
            TimeUnit.MILLISECONDS.sleep(50);
        }

        assertThat(replica.execute("SELECT * FROM posts").rows()).hasSize(1);

        // 4. Primary fails
        master.close();
        primaryDb.close();

        // 5. Promote replica to Primary
        assertThat(replica.isReadOnly()).isTrue();
        replica.promote();

        assertThat(replica.getRole()).isEqualTo(NodeRole.PRIMARY);
        assertThat(replica.isReadOnly()).isFalse();

        // 6. Promoted node can now accept write queries
        replica.execute("INSERT INTO posts VALUES (2, 'Promoted Leader Post')");

        QueryResult res = replica.execute("SELECT * FROM posts ORDER BY id");
        assertThat(res.rows()).hasSize(2);
        assertThat(res.rows().get(0).get("title")).isEqualTo("Hello World");
        assertThat(res.rows().get(1).get("title")).isEqualTo("Promoted Leader Post");
    }
}
