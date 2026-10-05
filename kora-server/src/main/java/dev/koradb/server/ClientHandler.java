package dev.koradb.server;

import dev.koradb.KoraDB;
import dev.koradb.catalog.Row;
import dev.koradb.execution.QueryResult;
import dev.koradb.mvcc.WriteConflictException;
import dev.koradb.protocol.*;
import dev.koradb.session.Session;
import dev.koradb.transaction.lock.DeadlockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles communication, authentication handshake, and SQL query execution
 * for a single connected client socket.
 */
public final class ClientHandler implements Runnable, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

    private final Socket socket;
    private final KoraDB db;
    private final long connectionId;
    private final java.util.function.Consumer<ClientHandler> onDisconnect;
    private final Session session;

    private volatile boolean running = true;
    private BufferedInputStream in;
    private BufferedOutputStream out;

    public ClientHandler(Socket socket, KoraDB db, long connectionId, java.util.function.Consumer<ClientHandler> onDisconnect) {
        this.socket = socket;
        this.db = db;
        this.connectionId = connectionId;
        this.onDisconnect = onDisconnect;
        this.session = db.createSession();
        this.session.setDefaultIsolationLevel(dev.koradb.transaction.IsolationLevel.SNAPSHOT);
    }

    @Override
    public void run() {
        log.info("Client connected: {} (connId={})", socket.getRemoteSocketAddress(), connectionId);
        try {
            in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());

            // 1. Handshake
            Message initialMsg = ProtocolCodec.readMessage(in);
            if (initialMsg == null) {
                return;
            }
            if (!(initialMsg instanceof ConnectMessage connect)) {
                ProtocolCodec.writeMessage(out, new ErrorMessage(ErrorMessage.ERR_PROTOCOL, "Expected CONNECT message"));
                return;
            }

            // Target database selection if requested
            if (connect.database() != null && !connect.database().isBlank()) {
                try {
                    db.execute(session, "USE " + connect.database());
                } catch (Exception e) {
                    ProtocolCodec.writeMessage(out, new ErrorMessage(ErrorMessage.ERR_SCHEMA, "Failed to use database '" + connect.database() + "': " + e.getMessage()));
                    return;
                }
            }

            ProtocolCodec.writeMessage(out, new ConnectOkMessage(
                    "KoraDB/0.1.0-SNAPSHOT",
                    connectionId,
                    "Connected to KoraDB server"
            ));

            // 2. Query processing loop
            while (running && !socket.isClosed()) {
                Message msg = ProtocolCodec.readMessage(in);
                if (msg == null || msg instanceof DisconnectMessage) {
                    log.debug("Client requested disconnect (connId={})", connectionId);
                    break;
                }

                if (msg instanceof PingMessage) {
                    ProtocolCodec.writeMessage(out, new PongMessage());
                } else if (msg instanceof QueryMessage qm) {
                    handleQuery(qm.sql());
                } else {
                    ProtocolCodec.writeMessage(out, new ErrorMessage(
                            ErrorMessage.ERR_PROTOCOL,
                            "Unsupported message type during query loop: " + msg.getType()
                    ));
                }
            }

        } catch (IOException e) {
            if (running) {
                log.debug("Connection dropped by client {} (connId={}): {}", socket.getRemoteSocketAddress(), connectionId, e.getMessage());
            }
        } catch (Exception e) {
            log.error("Unexpected error in client handler (connId={})", connectionId, e);
        } finally {
            close();
        }
    }

    private void handleQuery(String sql) throws IOException {
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }

        try {
            if (trimmed.equalsIgnoreCase("SHOW DATABASES")) {
                List<String> dbs = session.getCatalog().listDatabases();
                List<List<Object>> rows = new ArrayList<>();
                for (String dbName : dbs) {
                    rows.add(List.of(dbName));
                }
                ProtocolCodec.writeMessage(out, QueryResultMessage.rows(List.of("database"), rows));
                return;
            }

            if (trimmed.equalsIgnoreCase("SHOW TABLES")) {
                var activeDb = session.getCatalog().currentDatabase();
                if (activeDb.isEmpty()) {
                    ProtocolCodec.writeMessage(out, new ErrorMessage(ErrorMessage.ERR_SCHEMA, "No database selected. Use 'USE <dbname>;' first."));
                    return;
                }
                var database = session.getCatalog().getDatabase(activeDb.get());
                List<dev.koradb.catalog.Table> tables = database.listTables();
                List<List<Object>> rows = new ArrayList<>();
                for (var tbl : tables) {
                    rows.add(List.of(tbl.getName()));
                }
                ProtocolCodec.writeMessage(out, QueryResultMessage.rows(List.of("table_name"), rows));
                return;
            }

            if (trimmed.toUpperCase().startsWith("DESCRIBE ") || trimmed.toUpperCase().startsWith("DESC ")) {
                String tableName = trimmed.substring(trimmed.indexOf(' ') + 1).trim();
                var activeDb = session.getCatalog().currentDatabase();
                if (activeDb.isEmpty()) {
                    ProtocolCodec.writeMessage(out, new ErrorMessage(ErrorMessage.ERR_SCHEMA, "No database selected. Use 'USE <dbname>;' first."));
                    return;
                }
                var table = session.getCatalog().getDatabase(activeDb.get()).getTable(tableName);
                List<List<Object>> rows = new ArrayList<>();
                for (var col : table.getSchema().columns()) {
                    rows.add(List.of(col.name(), col.dataType().name()));
                }
                ProtocolCodec.writeMessage(out, QueryResultMessage.rows(List.of("column_name", "data_type"), rows));
                return;
            }

            if (trimmed.toUpperCase().startsWith("SET TRANSACTION ISOLATION LEVEL ")
                    || trimmed.toUpperCase().startsWith("SET ISOLATION LEVEL ")) {
                String levelStr = trimmed.substring(trimmed.lastIndexOf(' ') + 1).trim().toUpperCase();
                dev.koradb.transaction.IsolationLevel level = switch (levelStr) {
                    case "SERIALIZABLE" -> dev.koradb.transaction.IsolationLevel.SERIALIZABLE;
                    case "SNAPSHOT" -> dev.koradb.transaction.IsolationLevel.SNAPSHOT;
                    case "READ_COMMITTED" -> dev.koradb.transaction.IsolationLevel.READ_COMMITTED;
                    default -> throw new IllegalArgumentException("Unknown isolation level: " + levelStr);
                };
                session.setDefaultIsolationLevel(level);
                ProtocolCodec.writeMessage(out, QueryResultMessage.ddl());
                return;
            }

            QueryResult res = db.execute(session, sql);

            if (!res.columns().isEmpty()) {
                List<List<Object>> rows = new ArrayList<>(res.rows().size());
                for (Row r : res.rows()) {
                    List<Object> rowVals = new ArrayList<>(res.columns().size());
                    for (String col : res.columns()) {
                        rowVals.add(r.values().get(col));
                    }
                    rows.add(rowVals);
                }
                ProtocolCodec.writeMessage(out, QueryResultMessage.rows(res.columns(), rows));
            } else if (res.affectedRows() > 0) {
                ProtocolCodec.writeMessage(out, QueryResultMessage.dml(res.affectedRows()));
            } else {
                ProtocolCodec.writeMessage(out, QueryResultMessage.ddl());
            }

        } catch (dev.koradb.exception.ParseException pe) {
            ProtocolCodec.writeMessage(out, new ErrorMessage(ErrorMessage.ERR_PARSE, pe.getMessage()));
        } catch (dev.koradb.exception.SchemaException se) {
            ProtocolCodec.writeMessage(out, new ErrorMessage(ErrorMessage.ERR_SCHEMA, se.getMessage()));
        } catch (WriteConflictException wce) {
            ProtocolCodec.writeMessage(out, new ErrorMessage(ErrorMessage.ERR_CONFLICT, "Write conflict: " + wce.getMessage()));
        } catch (DeadlockException de) {
            ProtocolCodec.writeMessage(out, new ErrorMessage(ErrorMessage.ERR_CONFLICT, "Deadlock detected: " + de.getMessage()));
        } catch (dev.koradb.exception.ExecutionException ee) {
            ProtocolCodec.writeMessage(out, new ErrorMessage(ErrorMessage.ERR_EXECUTION, ee.getMessage()));
        } catch (Exception e) {
            ProtocolCodec.writeMessage(out, new ErrorMessage(ErrorMessage.ERR_GENERIC, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    @Override
    public synchronized void close() {
        if (!running) return;
        running = false;

        // Auto-rollback any uncommitted transaction on disconnect
        if (session.hasActiveTransaction()) {
            try {
                log.info("Aborting in-flight transaction on client disconnect (connId={})", connectionId);
                db.execute(session, "ROLLBACK");
            } catch (Exception ignored) {}
        }

        try {
            socket.close();
        } catch (IOException ignored) {}

        if (onDisconnect != null) {
            onDisconnect.accept(this);
        }
        log.info("Client disconnected: {} (connId={})", socket.getRemoteSocketAddress(), connectionId);
    }

    public long getConnectionId() {
        return connectionId;
    }

    public Session getSession() {
        return session;
    }
}
