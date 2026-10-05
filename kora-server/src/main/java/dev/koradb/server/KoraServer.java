package dev.koradb.server;

import dev.koradb.KoraDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-threaded TCP server for KoraDB.
 *
 * <p>Listens for client connections, establishes dedicated sessions, and dispatches
 * requests concurrently using Java 21 Virtual Threads.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   KoraServer server = new KoraServer(KoraServerConfig.inMemory(8765));
 *   server.start();
 *   // ...
 *   server.stop();
 * }</pre>
 */
public final class KoraServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KoraServer.class);

    private final KoraServerConfig config;
    private final KoraDB db;
    private final boolean ownsDb;
    private final Set<ClientHandler> activeHandlers = ConcurrentHashMap.newKeySet();
    private final AtomicLong connectionIdSeq = new AtomicLong(1);

    private ServerSocket serverSocket;
    private ExecutorService clientExecutor;
    private Thread acceptorThread;
    private volatile boolean running = false;

    public KoraServer(KoraServerConfig config) {
        this.config = config;
        if (config.dataDirectory() != null) {
            this.db = new KoraDB(config.dataDirectory());
        } else {
            this.db = new KoraDB();
        }
        this.ownsDb = true;
    }

    public KoraServer(KoraServerConfig config, KoraDB db) {
        this.config = config;
        this.db = db;
        this.ownsDb = false;
    }

    /**
     * Starts listening for client connections on the configured host and port.
     *
     * @throws IOException if the server socket cannot be bound
     */
    public synchronized void start() throws IOException {
        if (running) {
            throw new IllegalStateException("Server is already running");
        }

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.host(), config.port()), config.backlog());
        running = true;

        // Java 21 virtual threads for lightweight, high-concurrency client handling
        clientExecutor = Executors.newVirtualThreadPerTaskExecutor();

        acceptorThread = Thread.ofVirtual().name("koradb-acceptor").start(this::acceptConnections);

        log.info("KoraDB server started on {}:{} (dataDir={})",
                config.host(), getPort(),
                config.dataDirectory() != null ? config.dataDirectory() : ":memory:");
    }

    private void acceptConnections() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);

                long connId = connectionIdSeq.getAndIncrement();
                ClientHandler handler = new ClientHandler(
                        clientSocket,
                        db,
                        connId,
                        activeHandlers::remove
                );

                activeHandlers.add(handler);
                clientExecutor.submit(handler);

            } catch (SocketException se) {
                if (!running) {
                    break; // Normal shutdown
                }
                log.warn("SocketException in acceptor loop: {}", se.getMessage());
            } catch (IOException ioe) {
                if (running) {
                    log.error("I/O error accepting connection", ioe);
                }
            }
        }
    }

    /**
     * Stops the server, closes all active client connections, and shuts down resources.
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;

        log.info("Stopping KoraDB server on port {}...", getPort());

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }

        if (acceptorThread != null) {
            acceptorThread.interrupt();
        }

        for (ClientHandler handler : activeHandlers) {
            handler.close();
        }
        activeHandlers.clear();

        if (clientExecutor != null) {
            clientExecutor.shutdownNow();
            try {
                clientExecutor.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        if (ownsDb) {
            db.close();
        }

        log.info("KoraDB server stopped");
    }

    @Override
    public void close() {
        stop();
    }

    public int getPort() {
        if (serverSocket != null && serverSocket.isBound()) {
            return serverSocket.getLocalPort();
        }
        return config.port();
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    public int getActiveConnectionCount() {
        return activeHandlers.size();
    }

    public KoraDB getDb() {
        return db;
    }
}
