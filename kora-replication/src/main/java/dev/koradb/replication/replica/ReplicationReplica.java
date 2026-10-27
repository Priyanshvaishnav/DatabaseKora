package dev.koradb.replication.replica;

import dev.koradb.KoraDB;
import dev.koradb.catalog.DiskCatalog;
import dev.koradb.execution.QueryResult;
import dev.koradb.replication.model.NodeRole;
import dev.koradb.replication.model.ReadOnlyReplicaException;
import dev.koradb.replication.model.ReplicationStatus;
import dev.koradb.sql.ast.*;
import dev.koradb.sql.lexer.Lexer;
import dev.koradb.sql.lexer.Token;
import dev.koradb.sql.parser.Parser;
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

        if (db.getCatalog() instanceof DiskCatalog dc) {
            dc.setSuppressWalLogging(true);
        }
    }

    /**
     * Starts the replica network receiver.
     */
    public synchronized void start() throws IOException {
        walReceiver.start();
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
        this.role = NodeRole.PRIMARY;
        if (db.getCatalog() instanceof DiskCatalog dc) {
            dc.setSuppressWalLogging(false);
        }
        log.info("Replica '{}' successfully promoted to PRIMARY (Read-Write enabled)", config.replicaId());
    }

    /**
     * Executes a SQL statement. In REPLICA mode, mutating statements are rejected.
     */
    public QueryResult execute(String sql) {
        List<Token> tokens = new Lexer(sql).tokenize();
        Statement stmt = new Parser(tokens).parse();

        if (role == NodeRole.REPLICA && isMutatingStatement(stmt)) {
            throw new ReadOnlyReplicaException("Cannot execute mutating statement on read-only replica: " + sql);
        }

        return db.execute(sql);
    }

    private boolean isMutatingStatement(Statement stmt) {
        return stmt instanceof InsertStmt
                || stmt instanceof UpdateStmt
                || stmt instanceof DeleteStmt
                || stmt instanceof CreateTableStmt
                || stmt instanceof DropTableStmt
                || stmt instanceof CreateIndexStmt
                || stmt instanceof DropIndexStmt
                || stmt instanceof CreateDatabaseStmt
                || stmt instanceof DropDatabaseStmt
                || stmt instanceof TruncateStmt
                || stmt instanceof AlterTableStmt;
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
