package dev.koradb.server;

import dev.koradb.KoraDB;
import dev.koradb.catalog.DiskCatalog;
import dev.koradb.observability.ObservabilityEngine;
import dev.koradb.observability.exporter.MetricsExporterConfig;
import dev.koradb.observability.exporter.MetricsHttpServer;
import dev.koradb.server.http.KoraHttpConfig;
import dev.koradb.server.http.KoraHttpServer;
import dev.koradb.replication.master.ReplicationMaster;
import dev.koradb.replication.master.ReplicationMasterConfig;
import dev.koradb.replication.model.NodeRole;
import dev.koradb.replication.replica.ReplicationReplica;
import dev.koradb.replication.replica.ReplicationReplicaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Manages the lifecycle of a complete KoraDB node runtime, including the database engine,
 * SQL server, replication (master/replica), and observability engine.
 */
public final class ServerInstance implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ServerInstance.class);

    private final ServerBootstrapConfig config;
    private KoraDB db;
    private KoraServer server;
    private ReplicationMaster master;
    private ReplicationReplica replica;
    private ObservabilityEngine observabilityEngine;
    private MetricsHttpServer metricsServer;
    private KoraHttpServer httpServer;
    private volatile boolean running = false;

    public ServerInstance(ServerBootstrapConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * Starts all subsystems based on the configured role and parameters.
     */
    public synchronized void start() throws IOException {
        if (running) {
            throw new IllegalStateException("ServerInstance is already running");
        }

        log.info("Bootstrapping KoraDB node (role={}, host={}, port={}, dataDir={})",
                config.role(), config.host(), config.port(),
                config.dataDir() != null ? config.dataDir() : ":memory:");

        // 1. Initialize DB engine
        if (config.dataDir() != null) {
            Files.createDirectories(config.dataDir());
            this.db = new KoraDB(config.dataDir());
        } else {
            this.db = new KoraDB();
        }

        // 2. Initialize Observability & Prometheus exporter if enabled
        if (config.metricsEnabled()) {
            this.observabilityEngine = ObservabilityEngine.getInstance();
            if (db.getCatalog() instanceof DiskCatalog dc) {
                observabilityEngine.bindLogManagers(dc::getAllLogManagers);
                observabilityEngine.bindBufferPools(() -> dc.getAllOpenTableFiles().stream()
                        .map(tf -> tf.getBufferPool().getStats())
                        .toList());
            }
            observabilityEngine.bindTransactionManager(db.getTransactionManager());
            observabilityEngine.bindQueries(db);

            try {
                this.metricsServer = observabilityEngine.startHttpExporter(
                        MetricsExporterConfig.of(config.host(), config.metricsPort())
                );
                log.info("Observability HTTP metrics exporter running at http://{}:{}/metrics",
                        config.host(), config.metricsPort());
            } catch (Exception e) {
                log.warn("Failed to start metrics HTTP server on port {}: {}", config.metricsPort(), e.getMessage());
            }
        }

        // 3. Initialize replication according to role
        if (config.role() == NodeRole.PRIMARY) {
            if (db.getCatalog() instanceof DiskCatalog dc) {
                Function<String, Map<String, byte[]>> snapshotSupplier = dc::snapshotFiles;

                // The replication stream carries ONE database's WAL. Open it before attaching the
                // master (its LogManager only exists once the database is open), creating it on a
                // fresh data directory.
                String replDb = replicationDatabase();
                if (!dc.listDatabases().contains(replDb)) {
                    dc.createDatabase(replDb);
                    log.info("Created replicated database '{}'", replDb);
                }
                dc.useDatabase(replDb);

                ReplicationMasterConfig masterConfig = new ReplicationMasterConfig(
                        config.host(),
                        config.replicationPort(),
                        "primary-1",
                        config.replicationMode(),
                        1000L,
                        5000L,
                        100
                );
                this.master = new ReplicationMaster(masterConfig, dc.getLogManager(), snapshotSupplier);
                this.master.attachTo(db);
                this.master.start();
                log.info("Replication master started on {}:{} ({}), replicating database '{}'",
                        config.host(), config.replicationPort(), config.replicationMode(), replDb);
            }

            KoraServerConfig serverConfig = new KoraServerConfig(config.host(), config.port(), 128, config.dataDir(), false);
            this.server = new KoraServer(serverConfig, db);
            this.server.start();

        } else if (config.role() == NodeRole.REPLICA) {
            // Mirror the primary: the replicated database must exist and be selected so that
            // streamed WAL records are applied to it.
            if (db.getCatalog() instanceof DiskCatalog dc) {
                String replDb = replicationDatabase();
                if (!dc.listDatabases().contains(replDb)) {
                    dc.createDatabase(replDb);
                }
                dc.useDatabase(replDb);
            }
            ReplicationReplicaConfig replicaConfig = ReplicationReplicaConfig.of(
                    config.replicaId(),
                    config.primaryHost(),
                    config.primaryPort(),
                    config.replicationMode()
            );
            this.replica = new ReplicationReplica(db, replicaConfig);
            this.replica.start();
            log.info("Replication replica '{}' tracking primary at {}:{}",
                    config.replicaId(), config.primaryHost(), config.primaryPort());

            KoraServerConfig serverConfig = new KoraServerConfig(config.host(), config.port(), 128, config.dataDir(), true);
            this.server = new KoraServer(serverConfig, db);
            this.server.start();

        } else {
            // STANDALONE
            KoraServerConfig serverConfig = new KoraServerConfig(config.host(), config.port(), 128, config.dataDir(), false);
            this.server = new KoraServer(serverConfig, db);
            this.server.start();
        }

        // 4. Initialize HTTP REST query server if enabled
        if (config.httpEnabled()) {
            try {
                KoraHttpConfig httpConfig = KoraHttpConfig.of(
                        config.host(),
                        config.httpPort(),
                        config.role() == NodeRole.REPLICA
                );
                this.httpServer = new KoraHttpServer(httpConfig, db);
                this.httpServer.start();
                log.info("HTTP REST query server running at http://{}:{}/v1/query",
                        config.host(), httpServer.getPort());
            } catch (Exception e) {
                log.warn("Failed to start HTTP query server on port {}: {}", config.httpPort(), e.getMessage());
            }
        }

        this.running = true;
        log.info("KoraDB node operational on {}:{} (role={})", config.host(), server.getPort(), config.role());
    }

    /**
     * Gracefully stops all subsystems and flushes dirty pages/WAL to disk.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        log.info("Gracefully stopping KoraDB node...");

        if (httpServer != null) {
            try {
                httpServer.close();
            } catch (Exception e) {
                log.warn("Error stopping HTTP query server: {}", e.getMessage());
            }
        }

        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                log.warn("Error stopping SQL server: {}", e.getMessage());
            }
        }

        if (replica != null) {
            try {
                replica.close();
            } catch (Exception e) {
                log.warn("Error stopping replica: {}", e.getMessage());
            }
        }

        if (master != null) {
            try {
                master.close();
            } catch (Exception e) {
                log.warn("Error stopping master: {}", e.getMessage());
            }
        }

        if (metricsServer != null) {
            try {
                metricsServer.close();
            } catch (Exception e) {
                log.warn("Error stopping metrics server: {}", e.getMessage());
            }
        }

        if (db != null) {
            try {
                db.close();
            } catch (Exception e) {
                log.warn("Error closing database: {}", e.getMessage());
            }
        }

        log.info("KoraDB node stopped successfully.");
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running;
    }

    public ServerBootstrapConfig getConfig() {
        return config;
    }

    public KoraDB getDb() {
        return db;
    }

    public KoraServer getServer() {
        return server;
    }

    public ReplicationMaster getMaster() {
        return master;
    }

    public ReplicationReplica getReplica() {
        return replica;
    }

    public MetricsHttpServer getMetricsServer() {
        return metricsServer;
    }

    public KoraHttpServer getHttpServer() {
        return httpServer;
    }

    /**
     * Name of the database whose WAL a PRIMARY streams to replicas: system property
     * {@code kora.replication.db} (set by {@code --replication-db}), else env {@code KORA_REPLICATION_DB},
     * else {@code "default"}.
     */
    static String replicationDatabase() {
        String v = System.getProperty("kora.replication.db");
        if (v == null || v.isBlank()) v = System.getenv("KORA_REPLICATION_DB");
        return (v == null || v.isBlank()) ? "default" : v.trim().toLowerCase();
    }

}
