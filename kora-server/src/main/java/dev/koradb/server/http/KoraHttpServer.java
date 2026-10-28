package dev.koradb.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.koradb.KoraDB;
import dev.koradb.catalog.Row;
import dev.koradb.catalog.Table;
import dev.koradb.execution.QueryResult;
import dev.koradb.mvcc.WriteConflictException;
import dev.koradb.session.Session;
import dev.koradb.sql.ReadOnlyCheck;
import dev.koradb.transaction.lock.DeadlockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Built-in HTTP REST query server for KoraDB.
 *
 * <p>Exposes a JSON HTTP API on top of Java 21 Virtual Threads and standard
 * library {@link HttpServer}, enabling any programming language (Python, Node.js,
 * Go, Rust, cURL) to query KoraDB without drivers or third-party dependencies.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /v1/query} - Executes SQL query (JSON payload or raw SQL)</li>
 *   <li>{@code GET  /v1/databases} - Lists all available databases</li>
 *   <li>{@code GET  /v1/tables} - Lists tables and columns in a database</li>
 *   <li>{@code GET  /v1/health} - Node health status, uptime, and version</li>
 * </ul>
 */
public final class KoraHttpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KoraHttpServer.class);
    public static final String SERVER_VERSION = "0.1.0-SNAPSHOT";

    private final KoraHttpConfig config;
    private final KoraDB db;
    private final long startTimeMs = System.currentTimeMillis();

    private HttpServer server;
    private volatile boolean running = false;

    public KoraHttpServer(KoraHttpConfig config, KoraDB db) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.db = Objects.requireNonNull(db, "db must not be null");
    }

    /**
     * Starts listening for HTTP requests on the configured host and port.
     */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }

        server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // Register REST endpoints. Everything but health needs credentials when a password is set
        // (health stays open for load balancer and Kubernetes probes; it exposes no data).
        server.createContext("/v1/query", authenticated(this::handleQueryEndpoint));
        server.createContext("/query", authenticated(this::handleQueryEndpoint));

        server.createContext("/v1/databases", authenticated(this::handleDatabasesEndpoint));
        server.createContext("/databases", authenticated(this::handleDatabasesEndpoint));

        server.createContext("/v1/tables", authenticated(this::handleTablesEndpoint));
        server.createContext("/tables", authenticated(this::handleTablesEndpoint));

        server.createContext("/v1/health", this::handleHealthEndpoint);
        server.createContext("/health", this::handleHealthEndpoint);

        server.start();
        running = true;
        log.info("KoraDB HTTP REST query server listening on http://{}:{}/v1/query",
                config.host(), getPort());
    }

    /**
     * Gracefully stops the HTTP server.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (server != null) {
            server.stop(0);
            server = null;
            log.info("KoraDB HTTP REST query server stopped");
        }
    }

    @Override
    public void close() {
        stop();
    }

    public int getPort() {
        if (server == null) {
            return config.port();
        }
        return server.getAddress().getPort();
    }

    public boolean isRunning() {
        return running;
    }

    // =========================================================================
    // Request Handlers
    // =========================================================================

    /** Rejects requests without valid {@code Authorization: Basic ...} / {@code Bearer ...} credentials. */
    private com.sun.net.httpserver.HttpHandler authenticated(com.sun.net.httpserver.HttpHandler handler) {
        return exchange -> {
            boolean preflight = "OPTIONS".equalsIgnoreCase(exchange.getRequestMethod());
            if (!preflight && !config.credentials().acceptsAuthorizationHeader(
                    exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"KoraDB\", charset=\"UTF-8\"");
                sendJson(exchange, 401, Map.of(
                        "status", "error",
                        "code", "UNAUTHORIZED",
                        "error", "Authentication required: send 'Authorization: Basic base64(user:password)' or 'Authorization: Bearer <password>'"
                ));
                return;
            }
            handler.handle(exchange);
        };
    }

    private void handleQueryEndpoint(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            handleOptions(exchange);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of(
                    "status", "error",
                    "code", "METHOD_NOT_ALLOWED",
                    "error", "Method Not Allowed. Use POST for SQL queries."
            ));
            return;
        }

        String body = readRequestBody(exchange).trim();
        if (body.isEmpty()) {
            sendJson(exchange, 400, Map.of(
                    "status", "error",
                    "code", "BAD_REQUEST",
                    "error", "Request body is empty"
            ));
            return;
        }

        Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI());
        String sql = null;
        String targetDb = queryParams.get("database");
        boolean createRequested = Boolean.parseBoolean(queryParams.getOrDefault("create", "false"));

        if (body.startsWith("{")) {
            try {
                Map<String, Object> json = Json.parseObject(body);
                Object sqlVal = json.get("sql");
                if (sqlVal != null) {
                    sql = sqlVal.toString().trim();
                }
                Object dbVal = json.get("database");
                if (dbVal != null && targetDb == null) {
                    targetDb = dbVal.toString().trim();
                }
                Object createVal = json.get("create");
                if (createVal != null) {
                    createRequested = Boolean.parseBoolean(createVal.toString());
                }
            } catch (Exception e) {
                sendJson(exchange, 400, Map.of(
                        "status", "error",
                        "code", "MALFORMED_JSON",
                        "error", "Malformed JSON body: " + e.getMessage()
                ));
                return;
            }
        } else {
            // Raw SQL payload (e.g. from curl -d "SELECT ...")
            sql = body;
        }

        if (sql == null || sql.isBlank()) {
            sendJson(exchange, 400, Map.of(
                    "status", "error",
                    "code", "MISSING_SQL",
                    "error", "Missing 'sql' query parameter or field in request body"
            ));
            return;
        }

        Session session = db.createSession();
        session.setDefaultIsolationLevel(dev.koradb.transaction.IsolationLevel.SNAPSHOT);

        if (targetDb != null && !targetDb.isBlank()) {
            try {
                db.execute(session, "USE " + targetDb);
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                if (!msg.contains("does not exist")) {
                    // The database exists but could not be opened (e.g. recovery failed): report the
                    // real cause instead of masking it behind a failed CREATE ("already exists").
                    sendJson(exchange, 500, Map.of(
                            "status", "error",
                            "code", "STORAGE_ERROR",
                            "error", "Cannot open database '" + targetDb + "': " + msg
                    ));
                    return;
                }
                // Create the database only when the caller clearly intends to (M9): an explicit
                // create=true, or a CREATE statement. A query against a misspelled name must fail
                // instead of silently creating an empty database.
                boolean ddlIntent = sql.trim().toUpperCase(java.util.Locale.ROOT).startsWith("CREATE ");
                // Creating a database is a write: a read-only node never does it.
                if (config.readOnly() || (!createRequested && !ddlIntent)) {
                    sendJson(exchange, 404, Map.of(
                            "status", "error",
                            "code", "DATABASE_NOT_FOUND",
                            "error", "Database '" + targetDb + "' does not exist. Create it with CREATE DATABASE, "
                                    + "or send create=true to create it with this request."
                    ));
                    return;
                }
                try {
                    db.execute(session, "CREATE DATABASE " + targetDb);
                    db.execute(session, "USE " + targetDb);
                } catch (Exception ex) {
                    sendJson(exchange, 400, Map.of(
                            "status", "error",
                            "code", "SCHEMA_ERROR",
                            "error", "Failed to select or create database '" + targetDb + "': " + ex.getMessage()
                    ));
                    return;
                }
            }
        }

        if (config.readOnly()) {
            boolean writes;
            try {
                writes = ReadOnlyCheck.firstWrite(sql).isPresent();
            } catch (dev.koradb.exception.ParseException pe) {
                writes = false;     // the engine rejects it with the same parse error
            }
            if (writes) {
                sendJson(exchange, 403, Map.of(
                        "status", "error",
                        "code", "READ_ONLY",
                        "error", "Cannot execute mutating statement on read-only replica: " + sql
                ));
                return;
            }
        }

        long startNs = System.nanoTime();
        try {
            QueryResult result = db.execute(session, sql);
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "success");

            if (!result.columns().isEmpty()) {
                resp.put("columns", result.columns());

                List<List<Object>> rows = new ArrayList<>(result.rows().size());
                for (Row r : result.rows()) {
                    List<Object> rowVals = new ArrayList<>(result.columns().size());
                    for (String col : result.columns()) {
                        rowVals.add(r.values().get(col));
                    }
                    rows.add(rowVals);
                }
                resp.put("rows", rows);
                resp.put("rowCount", rows.size());
            } else if (result.affectedRows() > 0) {
                resp.put("affectedRows", result.affectedRows());
            } else {
                resp.put("message", "Statement executed successfully");
                resp.put("affectedRows", 0);
            }

            resp.put("executionTimeMs", durationMs);
            sendJson(exchange, 200, resp);

        } catch (dev.koradb.exception.ParseException pe) {
            sendJson(exchange, 400, Map.of(
                    "status", "error",
                    "code", "PARSE_ERROR",
                    "error", pe.getMessage()
            ));
        } catch (dev.koradb.exception.SchemaException se) {
            sendJson(exchange, 400, Map.of(
                    "status", "error",
                    "code", "SCHEMA_ERROR",
                    "error", se.getMessage()
            ));
        } catch (WriteConflictException wce) {
            sendJson(exchange, 409, Map.of(
                    "status", "error",
                    "code", "CONFLICT_ERROR",
                    "error", "Write conflict: " + wce.getMessage()
            ));
        } catch (DeadlockException de) {
            sendJson(exchange, 409, Map.of(
                    "status", "error",
                    "code", "DEADLOCK_ERROR",
                    "error", "Deadlock detected: " + de.getMessage()
            ));
        } catch (dev.koradb.exception.ExecutionException ee) {
            sendJson(exchange, 400, Map.of(
                    "status", "error",
                    "code", "EXECUTION_ERROR",
                    "error", ee.getMessage()
            ));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of(
                    "status", "error",
                    "code", "INTERNAL_ERROR",
                    "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
            ));
        }
    }

    private void handleDatabasesEndpoint(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            handleOptions(exchange);
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of(
                    "status", "error",
                    "code", "METHOD_NOT_ALLOWED",
                    "error", "Method Not Allowed"
            ));
            return;
        }

        List<String> databases = db.getCatalog().listDatabases();
        sendJson(exchange, 200, Map.of(
                "status", "success",
                "databases", databases
        ));
    }

    private void handleTablesEndpoint(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            handleOptions(exchange);
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of(
                    "status", "error",
                    "code", "METHOD_NOT_ALLOWED",
                    "error", "Method Not Allowed"
            ));
            return;
        }

        Map<String, String> params = parseQueryParams(exchange.getRequestURI());
        String dbName = params.get("database");
        if (dbName == null || dbName.isBlank()) {
            dbName = db.getCatalog().currentDatabase().orElse("default");
        }

        var databaseOpt = db.getCatalog().getDatabase(dbName);
        if (databaseOpt == null) {
            sendJson(exchange, 404, Map.of(
                    "status", "error",
                    "code", "NOT_FOUND",
                    "error", "Database '" + dbName + "' not found"
            ));
            return;
        }

        List<Map<String, Object>> tableList = new ArrayList<>();
        for (Table table : databaseOpt.listTables()) {
            Map<String, Object> tableInfo = new LinkedHashMap<>();
            tableInfo.put("name", table.getName());

            List<Map<String, Object>> columns = new ArrayList<>();
            for (var col : table.getSchema().columns()) {
                Map<String, Object> colInfo = new LinkedHashMap<>();
                colInfo.put("name", col.name());
                colInfo.put("type", col.dataType().name());
                columns.add(colInfo);
            }
            tableInfo.put("columns", columns);
            tableList.add(tableInfo);
        }

        sendJson(exchange, 200, Map.of(
                "status", "success",
                "database", dbName,
                "tables", tableList
        ));
    }

    private void handleHealthEndpoint(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            handleOptions(exchange);
            return;
        }

        long uptimeSeconds = (System.currentTimeMillis() - startTimeMs) / 1000L;
        sendJson(exchange, 200, Map.of(
                "status", "UP",
                "version", SERVER_VERSION,
                "readOnly", config.readOnly(),
                "uptimeSeconds", uptimeSeconds
        ));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void handleOptions(HttpExchange exchange) throws IOException {
        if (config.corsEnabled()) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With");
            exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400");
        }
        exchange.sendResponseHeaders(204, -1);
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object data) throws IOException {
        String json = Json.stringify(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        if (config.corsEnabled()) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With");
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (var in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, String> parseQueryParams(URI uri) {
        Map<String, String> params = new HashMap<>();
        String query = uri.getRawQuery();
        if (query != null && !query.isBlank()) {
            for (String pair : query.split("&")) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                    String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                    params.put(key, val);
                } else if (!pair.isBlank()) {
                    String key = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                    params.put(key, "");
                }
            }
        }
        return params;
    }
}
