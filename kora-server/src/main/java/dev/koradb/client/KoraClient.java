package dev.koradb.client;

import dev.koradb.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Client driver for connecting to a remote KoraDB server over TCP.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   try (KoraClient client = KoraClient.connect("127.0.0.1", 8765, "finance")) {
 *       ClientQueryResult res = client.execute("SELECT * FROM accounts;");
 *       for (List<Object> row : res.rows()) {
 *           System.out.println(row);
 *       }
 *   }
 * }</pre>
 */
public final class KoraClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KoraClient.class);
    private static final int DEFAULT_TIMEOUT_MS = 30_000;

    private final Socket socket;
    private final BufferedInputStream in;
    private final BufferedOutputStream out;
    private final long connectionId;
    private final String serverVersion;
    private volatile boolean closed = false;

    private KoraClient(Socket socket, BufferedInputStream in, BufferedOutputStream out, long connectionId, String serverVersion) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.connectionId = connectionId;
        this.serverVersion = serverVersion;
    }

    /**
     * Connects to a KoraDB server without an initial database selected.
     */
    public static KoraClient connect(String host, int port) throws IOException {
        return connect(host, port, "");
    }

    /**
     * Connects to a KoraDB server and specifies an initial target database.
     */
    public static KoraClient connect(String host, int port, String database) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), DEFAULT_TIMEOUT_MS);
        socket.setSoTimeout(DEFAULT_TIMEOUT_MS);
        socket.setTcpNoDelay(true);

        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());

        // Perform handshake
        ConnectMessage connectMsg = new ConnectMessage(ConnectMessage.CURRENT_PROTOCOL_VERSION, "KoraClient/0.1.0", database);
        ProtocolCodec.writeMessage(out, connectMsg);

        Message resp = ProtocolCodec.readMessage(in);
        if (resp == null) {
            socket.close();
            throw new KoraClientException("Connection closed by server during handshake");
        }
        if (resp instanceof ErrorMessage err) {
            socket.close();
            throw new KoraClientException(err.errorCode(), err.message());
        }
        if (!(resp instanceof ConnectOkMessage ok)) {
            socket.close();
            throw new ProtocolException("Unexpected handshake response: " + resp.getType());
        }

        log.debug("Connected to KoraDB server: {} (connId={})", ok.serverVersion(), ok.connectionId());
        return new KoraClient(socket, in, out, ok.connectionId(), ok.serverVersion());
    }

    /**
     * Executes a SQL statement on the remote server and returns the result.
     *
     * @param sql SQL text
     * @return the {@link ClientQueryResult}
     * @throws KoraClientException if the server returned an error or communication failed
     */
    public synchronized ClientQueryResult execute(String sql) {
        ensureOpen();
        long start = System.currentTimeMillis();
        try {
            ProtocolCodec.writeMessage(out, new QueryMessage(sql));
            Message resp = ProtocolCodec.readMessage(in);
            long latency = System.currentTimeMillis() - start;

            if (resp == null) {
                close();
                throw new KoraClientException("Connection dropped by server");
            }
            if (resp instanceof ErrorMessage err) {
                throw new KoraClientException(err.errorCode(), err.message());
            }
            if (resp instanceof QueryResultMessage qrm) {
                return new ClientQueryResult(
                        qrm.isQuery(),
                        qrm.affectedRows(),
                        qrm.message(),
                        qrm.columns(),
                        qrm.rows(),
                        latency
                );
            }
            throw new ProtocolException("Unexpected response type to query: " + resp.getType());
        } catch (IOException e) {
            close();
            throw new KoraClientException("Network I/O error during query execution: " + e.getMessage(), e);
        }
    }

    /**
     * Sends a heartbeat PING and awaits PONG.
     *
     * @return true if pong was received successfully
     */
    public synchronized boolean ping() {
        ensureOpen();
        try {
            ProtocolCodec.writeMessage(out, new PingMessage());
            Message resp = ProtocolCodec.readMessage(in);
            return resp instanceof PongMessage;
        } catch (IOException e) {
            close();
            return false;
        }
    }

    public long getConnectionId() {
        return connectionId;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public boolean isClosed() {
        return closed || socket.isClosed();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            if (!socket.isClosed() && socket.isConnected()) {
                try {
                    ProtocolCodec.writeMessage(out, new DisconnectMessage());
                } catch (Exception ignored) {}
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private void ensureOpen() {
        if (closed || socket.isClosed()) {
            throw new IllegalStateException("Client connection is closed");
        }
    }
}
