package dev.koradb.replication.replica;

import dev.koradb.KoraDB;
import dev.koradb.catalog.DiskCatalog;
import dev.koradb.execution.QueryResult;
import dev.koradb.replication.model.NodeRole;
import dev.koradb.replication.model.ReadOnlyReplicaException;
import dev.koradb.replication.model.ReplicationStatus;
import dev.koradb.sql.ReadOnlyCheck;
import dev.koradb.storage.TableFile;
import dev.koradb.storage.wal.LogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Controller for a Replica node managing local database state, WAL receiving and applying,
 * read-only query enforcement, and failover promotion.
 */
public final class ReplicationReplica implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReplicationReplica.class);
    private static final long CATCH_UP_WAIT_MS = 60_000L;

    private final KoraDB db;
    private final ReplicationReplicaConfig config;
    private final WalApplier walApplier;
    private final WalReceiver walReceiver;

    private volatile NodeRole role = NodeRole.REPLICA;

    public ReplicationReplica(KoraDB db, ReplicationReplicaConfig config) {
        this.db = Objects.requireNonNull(db, "db must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");

        // Set up TableFile resolver and log manager from catalog
        Function<String, TableFile> resolver = tableName -> {
            if (db.getCatalog() instanceof DiskCatalog dc) {
                return dc.getTableFile(tableName);
            }
            return null;
        };

        LogManager localLogManager = null;
        if (db.getCatalog() instanceof DiskCatalog dc) {
            localLogManager = dc.getLogManager();
        }

        this.walApplier = new WalApplier(db.getCatalog(), db.getTransactionManager(), resolver);
        this.walReceiver = new WalReceiver(config, localLogManager, walApplier, this::unpackSnapshot);

        db.getTransactionManager().setFollowing(true);
        if (db.getCatalog() instanceof DiskCatalog dc) {
            dc.setSuppressWalLogging(true);
            dc.rebuildIndexes();
        }
    }

    /**
     * Starts the replica network receiver and waits until it has received what the primary had
     * when it connected. A replica does not keep the WAL it receives, so only replaying it again
     * tells which rows on its pages belong to unfinished transactions; until then it must not serve
     * reads. If this takes longer than {@link #CATCH_UP_WAIT_MS}, it carries on and logs a warning.
     */
    public synchronized void start() throws IOException {
        walReceiver.start();
        if (!walReceiver.awaitCatchUp(CATCH_UP_WAIT_MS)) {
            log.warn("Replica '{}' has not caught up with the primary after {} ms: until it has, reads may show "
                    + "changes of transactions that did not commit", config.replicaId(), CATCH_UP_WAIT_MS);
        }
        log.info("ReplicationReplica '{}' started, tracking Primary at {}:{}",
                config.replicaId(), config.primaryHost(), config.primaryPort());
    }

    /**
     * Promotes this replica to PRIMARY role during a failover event.
     *
     * <p>Shuts down the replication stream receiver, drains any remaining updates,
     * and lifts read-only restrictions.
     */
    public synchronized void promote() {
        if (role == NodeRole.PRIMARY) {
            return;
        }

        log.info("Promoting Replica '{}' to PRIMARY", config.replicaId());
        walReceiver.close();
        if (!walReceiver.hasCaughtUp()) {
            log.warn("Promoting replica '{}' before it caught up with the primary: it is missing committed changes, "
                    + "and its pages may hold changes of unfinished transactions it does not know about", config.replicaId());
        }
        int rolledBack = walApplier.rollBackInFlight();
        if (rolledBack > 0) {
            log.info("Rolled back {} transaction(s) the primary had not finished", rolledBack);
        }
        db.getTransactionManager().setFollowing(false);
        this.role = NodeRole.PRIMARY;
        if (db.getCatalog() instanceof DiskCatalog dc) {
            dc.prepareForPromotion();
            dc.setSuppressWalLogging(false);
        }
        log.info("Replica '{}' successfully promoted to PRIMARY (Read-Write enabled)", config.replicaId());
    }

    /**
     * Executes a SQL statement. In REPLICA mode, mutating statements are rejected.
     */
    public QueryResult execute(String sql) {
        // Every statement of a multi-statement string is checked: the engine runs all of them.
        if (role == NodeRole.REPLICA && ReadOnlyCheck.firstWrite(sql).isPresent()) {
            throw new ReadOnlyReplicaException("Cannot execute mutating statement on read-only replica: " + sql);
        }

        return db.execute(sql);
    }

    private void unpackSnapshot(Map<String, byte[]> files) {
        if (db.getCatalog() instanceof DiskCatalog dc) {
            Path dbDir = dc.getDataDir();
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                Path target = dbDir.resolve(entry.getKey());
                try {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.write(target, entry.getValue());
                } catch (IOException e) {
                    log.warn("Failed unpacking snapshot file {}", target, e);
                }
            }
        }
    }

    public boolean isReadOnly() {
        return role == NodeRole.REPLICA;
    }

    public NodeRole getRole() {
        return role;
    }

    public ReplicationStatus getStatus() {
        if (role == NodeRole.PRIMARY) {
            return ReplicationStatus.primary(config.mode(), walApplier.getAppliedLsn(), List.of());
        }

        long lagBytes = Math.max(0L, walReceiver.getLastReceivedLsn() - walApplier.getAppliedLsn());
        long lagMs = Math.max(0L, System.currentTimeMillis() - walReceiver.getLastHeartbeatTime());
        return ReplicationStatus.replica(
                config.mode(),
                walReceiver.getLastReceivedLsn(),
                walApplier.getAppliedLsn(),
                lagBytes,
                lagMs,
                walReceiver.isConnected()
        );
    }

    public KoraDB getDatabase() {
        return db;
    }

    public WalReceiver getWalReceiver() {
        return walReceiver;
    }

    public WalApplier getWalApplier() {
        return walApplier;
    }

    @Override
    public synchronized void close() {
        walReceiver.close();
        db.close();
    }
}
