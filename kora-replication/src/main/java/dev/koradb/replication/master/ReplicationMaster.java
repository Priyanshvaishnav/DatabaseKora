package dev.koradb.replication.master;

import dev.koradb.replication.model.ReplicationStatus;
import dev.koradb.storage.wal.LogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Primary node replication server accepting connections from Replicas and coordinating WAL streams.
 */
public final class ReplicationMaster implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReplicationMaster.class);

    private final ReplicationMasterConfig config;
    private final LogManager logManager;
    private final Function<String, Map<String, byte[]>> snapshotSupplier;
    private final SyncReplicationCoordinator syncCoordinator;
    private final Map<String, WalSender> activeSenders = new ConcurrentHashMap<>();

    private ServerSocket serverSocket;
    private ExecutorService virtualThreadExecutor;
    private Thread acceptorThread;
    private volatile boolean running = false;
    private final LogManager.WalFlushListener flushListener;

    public ReplicationMaster(ReplicationMasterConfig config,
                             LogManager logManager,
                             Function<String, Map<String, byte[]>> snapshotSupplier) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.logManager = Objects.requireNonNull(logManager, "logManager must not be null");
        this.snapshotSupplier = snapshotSupplier;
        this.syncCoordinator = new SyncReplicationCoordinator();
        this.flushListener = lsn -> notifySenders();
    }

    private dev.koradb.KoraDB attachedDb = null;

    /**
     * Attaches this replication master to a primary KoraDB instance.
     * If SYNC mode is configured, automatically registers synchronous commit hooks.
     */
    public synchronized void attachTo(dev.koradb.KoraDB db) {
        this.attachedDb = db;
        if (config.defaultMode() == dev.koradb.replication.model.ReplicationMode.SYNC && db != null) {
            db.getTransactionManager().setCommitHook(this::awaitCommit);
        }
    }

    /**
     * Starts the replication master server.
     */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }

        this.serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.host(), config.port()));

        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.running = true;

        this.logManager.addFlushListener(flushListener);

        this.acceptorThread = Thread.ofVirtual().name("ReplicationMaster-Acceptor").start(this::runAcceptorLoop);
        log.info("ReplicationMaster started on {}:{} (default mode: {})",
                config.host(), getPort(), config.defaultMode());
    }

    private void runAcceptorLoop() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                virtualThreadExecutor.submit(() -> handleNewReplica(clientSocket));
            } catch (IOException e) {
                if (running) {
                    log.warn("Error accepting replication connection: {}", e.getMessage());
                }
            }
        }
    }

    private void handleNewReplica(Socket socket) {
        WalSender sender = new WalSender(socket, logManager, snapshotSupplier, syncCoordinator, config);
        try {
            sender.start();
            ReplicaInfo info = sender.getReplicaInfo();
            if (info != null) {
                activeSenders.put(info.getReplicaId(), sender);
            }
        } catch (Exception e) {
            log.warn("Failed starting WAL streaming with {}: {}", socket.getRemoteSocketAddress(), e.getMessage());
            sender.close();
        }
    }

    private void notifySenders() {
        for (WalSender sender : activeSenders.values()) {
            sender.notifyNewWalAvailable();
        }
    }

    /**
     * Suspends the calling thread until synchronous replicas have flushed the commit LSN.
     */
    public void awaitCommit(long commitLsn) {
        syncCoordinator.awaitCommit(commitLsn, config.syncTimeoutMs());
    }

    public SyncReplicationCoordinator getSyncCoordinator() {
        return syncCoordinator;
    }

    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : config.port();
    }

    public List<ReplicaInfo> getConnectedReplicas() {
        List<ReplicaInfo> list = new ArrayList<>();
        activeSenders.entrySet().removeIf(e -> {
            WalSender sender = e.getValue();
            ReplicaInfo info = sender.getReplicaInfo();
            if (info != null) {
                list.add(info);
                return false;
            }
            return true;
        });
        return Collections.unmodifiableList(list);
    }

    public ReplicationStatus getStatus() {
        return ReplicationStatus.primary(config.defaultMode(), logManager.getFlushedLsn(), getConnectedReplicas());
    }

    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        running = false;
        logManager.removeFlushListener(flushListener);

        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}

        for (WalSender sender : activeSenders.values()) {
            sender.close();
        }
        activeSenders.clear();

        syncCoordinator.close();

        if (attachedDb != null && attachedDb.getTransactionManager() != null) {
            attachedDb.getTransactionManager().setCommitHook(null);
        }

        if (virtualThreadExecutor != null) {
            virtualThreadExecutor.shutdownNow();
        }
        log.info("ReplicationMaster stopped");
    }
}
