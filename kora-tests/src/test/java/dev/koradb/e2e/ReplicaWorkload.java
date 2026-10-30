package dev.koradb.e2e;

import dev.koradb.KoraDB;
import dev.koradb.catalog.DiskCatalog;
import dev.koradb.replication.model.ReplicationMode;
import dev.koradb.replication.replica.ReplicationReplica;
import dev.koradb.replication.replica.ReplicationReplicaConfig;

import java.nio.file.Path;

/**
 * Child-process replica for the kill -9 tests: follows a primary until table {@code t} has
 * {@code rows} rows, optionally gets promoted and commits changes of its own, prints READY and
 * waits to be killed.
 *
 * <p>Arguments: data directory, primary port, rows, "replica" or "promote".
 */
public final class ReplicaWorkload {

    public static void main(String[] args) throws Exception {
        KoraDB db = new KoraDB(Path.of(args[0]));
        var catalog = (DiskCatalog) db.getCatalog();
        if (!catalog.listDatabases().contains("d")) catalog.createDatabase("d");
        catalog.useDatabase("d");
        ReplicationReplica replica = new ReplicationReplica(db,
                ReplicationReplicaConfig.of("child", "127.0.0.1", Integer.parseInt(args[1]), ReplicationMode.ASYNC));
        replica.start();

        long rows = Long.parseLong(args[2]);
        long deadline = System.currentTimeMillis() + 60_000;
        while (true) {
            try {
                long seen = E2e.count(db, "SELECT COUNT(*) FROM t");
                if (seen == rows) break;
                if (System.currentTimeMillis() > deadline) {
                    System.out.println("Gave up waiting: table t has " + seen + " rows, expected " + rows);
                    System.exit(1);          // the test sees no READY and fails instead of hanging
                }
            } catch (RuntimeException tableNotThereYet) {
                if (System.currentTimeMillis() > deadline) {
                    System.out.println("Gave up waiting for table t: " + tableNotThereYet.getMessage());
                    System.exit(1);
                }
            }
            Thread.sleep(50);
        }
        if (args[3].equals("promote")) {
            replica.promote();
            db.execute("INSERT INTO t VALUES (9001, 1, 'after promotion')");
            db.execute("UPDATE t SET v = 'changed after promotion' WHERE id = 5");
            db.execute("DELETE FROM t WHERE id = 6");
        }
        System.out.println("READY");
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
