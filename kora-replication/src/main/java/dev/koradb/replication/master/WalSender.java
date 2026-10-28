package dev.koradb.replication.master;

import dev.koradb.replication.model.ReplicationMode;
import dev.koradb.replication.protocol.*;
import dev.koradb.storage.wal.LogIterator;
import dev.koradb.storage.wal.LogManager;
import dev.koradb.storage.wal.LogRecord;
import dev.koradb.storage.wal.LogRecordCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Handles replication streaming to a single connected Replica over TCP.
 */
public final class WalSender implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WalSender.class);

    private final Socket socket;
    private final LogManager logManager;
    private final Function<String, Map<String, byte[]>> snapshotSupplier;
    private final SyncReplicationCoordinator syncCoordinator;
    private final ReplicationMasterConfig config;

    private BufferedInputStream in;
    private BufferedOutputStream out;
    private volatile ReplicaInfo replicaInfo;
    private volatile boolean running = true;
    private final Object streamLock = new Object();

    private Thread senderThread;
    private Thread receiverThread;

    public WalSender(Socket socket,
                     LogManager logManager,
                     Function<String, Map<String, byte[]>> snapshotSupplier,
                     SyncReplicationCoordinator syncCoordinator,
                     ReplicationMasterConfig config) {
        this.socket = Objects.requireNonNull(socket, "socket must not be null");
        this.logManager = Objects.requireNonNull(logManager, "logManager must not be null");
        this.snapshotSupplier = snapshotSupplier;
        this.syncCoordinator = Objects.requireNonNull(syncCoordinator, "syncCoordinator must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * Starts the replication session with the connected replica.
     */
    public void start() throws IOException {
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());

        // Step 1: Handshake
        ReplicationMessage firstMsg = ReplicationCodec.readMessage(in);
        if (!(firstMsg instanceof HandshakeRequest req)) {
            ReplicationCodec.writeMessage(
                    new HandshakeResponse(config.primaryId(), 0L, 1, false, "Expected HandshakeRequest"), out);
            close();
            return;
        }

        // The snapshot and WAL stream carry every row: a primary with a password only serves
        // replicas that present it.
        if (!config.authToken().isEmpty() && !java.security.MessageDigest.isEqual(
                config.authToken().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                req.authToken().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            log.warn("Rejected replica '{}' from {}: authentication failed", req.replicaId(), socket.getRemoteSocketAddress());
            ReplicationCodec.writeMessage(
                    new HandshakeResponse(config.primaryId(), 0L, 1, false, "Authentication failed"), out);
            close();
            return;
        }

        ReplicationMode mode = req.mode() != null ? req.mode() : config.defaultMode();
        this.replicaInfo = new ReplicaInfo(req.replicaId(), socket.getRemoteSocketAddress(), mode, System.currentTimeMillis());
        this.replicaInfo.setLastAckFlushedLsn(req.lastFlushedLsn());
        this.replicaInfo.setLastAckAppliedLsn(req.lastFlushedLsn());

        long currentFlushedLsn = logManager.getFlushedLsn();
        ReplicationCodec.writeMessage(
                new HandshakeResponse(config.primaryId(), currentFlushedLsn, 1, true, "Handshake accepted"), out);

        log.info("Replica '{}' connected from {} with mode {} (starting from LSN {})",
                req.replicaId(), socket.getRemoteSocketAddress(), mode, req.lastFlushedLsn());

        // Step 2: Start bidirectional Virtual Threads
        this.receiverThread = Thread.ofVirtual().name("WalSender-Rx-" + req.replicaId()).start(this::runAckReceiver);
        this.senderThread = Thread.ofVirtual().name("WalSender-Tx-" + req.replicaId()).start(() -> runWalStreamer(req.lastFlushedLsn()));
    }

    private void runWalStreamer(long startLsn) {
        long streamingLsn = Math.max(startLsn, LogManager.WAL_HEADER_SIZE);

        while (running && !socket.isClosed()) {
            try {
                long currentFlushedLsn = logManager.getFlushedLsn();

                if (streamingLsn < currentFlushedLsn) {
                    List<LogRecord> batch = new ArrayList<>();
                    long batchStartLsn = streamingLsn;
                    long batchEndLsn = streamingLsn;

                    try (LogIterator it = new LogIterator(logManager.getWalPath(), streamingLsn)) {
                        while (it.hasNext() && batch.size() < config.batchMaxRecords()) {
                            LogRecord r = it.next();
                            batch.add(r);
                            batchEndLsn = r.getLsn() + LogRecordCodec.calculateEncodedLength(r);
                        }
                    }

                    if (!batch.isEmpty()) {
                        WalDataMessage dataMsg = new WalDataMessage(batchStartLsn, batchEndLsn, batch, System.currentTimeMillis());
                        synchronized (streamLock) {
                            ReplicationCodec.writeMessage(dataMsg, out);
                        }
                        streamingLsn = batchEndLsn;
                        continue;
                    }
                }

                // If up to date, wait or send heartbeat
                long waitStart = System.currentTimeMillis();
                synchronized (this) {
                    try {
                        wait(Math.min(config.heartbeatIntervalMs(), 100L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                if (System.currentTimeMillis() - waitStart >= config.heartbeatIntervalMs() / 2) {
                    HeartbeatMessage hb = new HeartbeatMessage(logManager.getFlushedLsn(), System.currentTimeMillis());
                    synchronized (streamLock) {
                        ReplicationCodec.writeMessage(hb, out);
                    }
                }
            } catch (Exception e) {
                if (running) {
                    log.warn("Error streaming WAL to replica {}: {}",
                            replicaInfo != null ? replicaInfo.getReplicaId() : "unknown", e.getMessage());
                }
                break;
            }
        }
        close();
    }

    private void runAckReceiver() {
        while (running && !socket.isClosed()) {
            try {
                ReplicationMessage msg = ReplicationCodec.readMessage(in);
                if (msg == null) {
                    break; // Clean EOF
                }

                switch (msg) {
                    case WalAckMessage ack -> {
                        if (replicaInfo != null) {
                            replicaInfo.setLastAckFlushedLsn(ack.flushedLsn());
                            replicaInfo.setLastAckAppliedLsn(ack.appliedLsn());
                            replicaInfo.setLastHeartbeatTimestamp(ack.timestamp());
                            syncCoordinator.onReplicaFlushed(ack.replicaId(), replicaInfo.getMode(), ack.flushedLsn());
                        }
                    }
                    case HeartbeatAckMessage hback -> {
                        if (replicaInfo != null) {
                            replicaInfo.setLastHeartbeatTimestamp(hback.timestamp());
                        }
                    }
                    case SnapshotRequest sreq -> {
                        handleSnapshotRequest(sreq);
                    }
                    default -> log.debug("Unexpected message from replica: {}", msg.getType());
                }
            } catch (Exception e) {
                if (running) {
                    log.warn("Error receiving ACK from replica {}: {}",
                            replicaInfo != null ? replicaInfo.getReplicaId() : "unknown", e.getMessage());
                }
                break;
            }
        }
        close();
    }

    private void handleSnapshotRequest(SnapshotRequest req) throws IOException {
        if (snapshotSupplier != null) {
            Map<String, byte[]> files = snapshotSupplier.apply(req.databaseName());
            SnapshotResponse resp = new SnapshotResponse(req.databaseName(), logManager.getFlushedLsn(), files);
            synchronized (streamLock) {
                ReplicationCodec.writeMessage(resp, out);
            }
            log.info("Sent snapshot of '{}' ({} files, LSN {}) to replica '{}'",
                    req.databaseName(), files.size(), logManager.getFlushedLsn(), req.replicaId());
        }
    }

    public ReplicaInfo getReplicaInfo() {
        return replicaInfo;
    }

    public synchronized void notifyNewWalAvailable() {
        notifyAll();
    }

    @Override
    public void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {}
        if (senderThread != null) {
            senderThread.interrupt();
        }
        if (receiverThread != null) {
            receiverThread.interrupt();
        }
    }
}
