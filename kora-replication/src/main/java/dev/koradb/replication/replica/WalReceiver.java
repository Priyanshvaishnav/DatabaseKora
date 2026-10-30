package dev.koradb.replication.replica;

import dev.koradb.replication.protocol.*;
import dev.koradb.storage.wal.LogManager;
import dev.koradb.storage.wal.LogRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Background networking receiver running on a Replica node that connects to the Primary,
 * receives streamed WAL records, flushes them to local WAL, and triggers application.
 */
public final class WalReceiver implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WalReceiver.class);

    private final ReplicationReplicaConfig config;
    private final LogManager localLogManager;
    private final WalApplier walApplier;
    private final Consumer<Map<String, byte[]>> snapshotHandler;

    private final AtomicLong lastReceivedLsn = new AtomicLong(LogManager.WAL_HEADER_SIZE);
    private final AtomicLong lastHeartbeatTime = new AtomicLong(System.currentTimeMillis());

    private volatile boolean running = false;
    private volatile boolean connected = false;
    private Socket socket;
    private BufferedInputStream in;
    private BufferedOutputStream out;
    private Thread receiverThread;
    private final Object writeLock = new Object();

    public WalReceiver(ReplicationReplicaConfig config,
                       LogManager localLogManager,
                       WalApplier walApplier,
                       Consumer<Map<String, byte[]>> snapshotHandler) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.localLogManager = localLogManager;
        this.walApplier = Objects.requireNonNull(walApplier, "walApplier must not be null");
        this.snapshotHandler = snapshotHandler;
    }

    /**
     * Connects to the Primary and starts the WAL receiver loop on a Virtual Thread.
     */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }

        connectToPrimary();
        this.running = true;
        this.receiverThread = Thread.ofVirtual().name("WalReceiver-" + config.replicaId()).start(this::runReceiverLoop);
    }

    private void connectToPrimary() throws IOException {
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress(config.primaryHost(), config.primaryPort()), 5000);
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());

        long startLsn = walApplier.getAppliedLsn();
        HandshakeRequest req = new HandshakeRequest(config.replicaId(), startLsn, config.mode(), config.authToken());
        ReplicationCodec.writeMessage(req, out);

        ReplicationMessage respMsg = ReplicationCodec.readMessage(in);
        if (!(respMsg instanceof HandshakeResponse resp)) {
            throw new ReplicationProtocolException("Expected HandshakeResponse, got " +
                    (respMsg != null ? respMsg.getType() : "EOF"));
        }

        if (!resp.accepted()) {
            throw new ReplicationProtocolException("Handshake rejected by primary: " + resp.message());
        }

        this.connected = true;
        this.lastReceivedLsn.set(resp.currentLsn());
        this.lastHeartbeatTime.set(System.currentTimeMillis());

        log.info("Replica '{}' connected to Primary at {}:{} (Primary LSN: {})",
                config.replicaId(), config.primaryHost(), config.primaryPort(), resp.currentLsn());
    }

    private void runReceiverLoop() {
        while (running && !socket.isClosed()) {
            try {
                ReplicationMessage msg = ReplicationCodec.readMessage(in);
                if (msg == null) {
                    log.info("Primary closed replication stream (EOF)");
                    break;
                }

                switch (msg) {
                    case WalDataMessage data -> handleWalData(data);
                    case HeartbeatMessage hb -> handleHeartbeat(hb);
                    case SnapshotResponse sresp -> handleSnapshot(sresp);
                    default -> log.debug("Unexpected message from primary: {}", msg.getType());
                }
            } catch (Exception e) {
                if (running) {
                    log.warn("Error in replication receive loop: {}", e.getMessage());
                }
                break;
            }
        }
        connected = false;
        closeSocket();
    }

    private void handleWalData(WalDataMessage data) throws IOException {
        lastHeartbeatTime.set(System.currentTimeMillis());

        // 1. Apply records onto replica storage
        walApplier.applyRecords(data.records());
        lastReceivedLsn.set(data.endLsn());

        // 2. Send ACK to Primary
        WalAckMessage ack = new WalAckMessage(
                config.replicaId(),
                data.endLsn(),
                walApplier.getAppliedLsn(),
                System.currentTimeMillis()
        );

        synchronized (writeLock) {
            ReplicationCodec.writeMessage(ack, out);
        }
    }

    private void handleHeartbeat(HeartbeatMessage hb) throws IOException {
        lastHeartbeatTime.set(System.currentTimeMillis());
        lastReceivedLsn.accumulateAndGet(hb.primaryLsn(), Math::max);

        HeartbeatAckMessage ack = new HeartbeatAckMessage(
                config.replicaId(),
                walApplier.getAppliedLsn(),
                System.currentTimeMillis()
        );

        synchronized (writeLock) {
            ReplicationCodec.writeMessage(ack, out);
        }
    }

    private void handleSnapshot(SnapshotResponse sresp) throws IOException {
        log.info("Received database snapshot for '{}' (LSN {})", sresp.databaseName(), sresp.snapshotLsn());
        if (snapshotHandler != null) {
            snapshotHandler.accept(sresp.files());
        }
        walApplier.setAppliedLsn(sresp.snapshotLsn());
        lastReceivedLsn.set(sresp.snapshotLsn());

        WalAckMessage ack = new WalAckMessage(
                config.replicaId(),
                sresp.snapshotLsn(),
                sresp.snapshotLsn(),
                System.currentTimeMillis()
        );
        synchronized (writeLock) {
            ReplicationCodec.writeMessage(ack, out);
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public long getLastReceivedLsn() {
        return lastReceivedLsn.get();
    }

    public long getLastHeartbeatTime() {
        return lastHeartbeatTime.get();
    }

    public WalApplier getWalApplier() {
        return walApplier;
    }

    private void closeSocket() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {}
    }

    @Override
    public synchronized void close() {
        running = false;
        connected = false;
        closeSocket();
        if (receiverThread != null) {
            receiverThread.interrupt();
            try {
                receiverThread.join(5000);      // no batch may still be applying when a replica is promoted
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
