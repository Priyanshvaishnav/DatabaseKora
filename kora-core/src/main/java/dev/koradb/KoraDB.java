package dev.koradb;

import dev.koradb.catalog.Catalog;
import dev.koradb.catalog.DiskCatalog;
import dev.koradb.catalog.InMemoryCatalog;
import dev.koradb.execution.QueryResult;
import dev.koradb.execution.StatementExecutor;
import dev.koradb.sql.ast.Statement;
import dev.koradb.sql.lexer.Lexer;
import dev.koradb.sql.lexer.Token;
import dev.koradb.sql.parser.Parser;
import dev.koradb.transaction.Transaction;
import dev.koradb.transaction.TransactionManager;
import dev.koradb.transaction.lock.DeadlockException;
import dev.koradb.transaction.lock.LockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import dev.koradb.catalog.Row;

/**
 * The public entry point to KoraDB.
 *
 * <p>This class wires together the full SQL execution pipeline:
 * <pre>
 *   SQL string
 *     → Lexer    (SQL → tokens)
 *     → Parser   (tokens → AST)
 *     → Executor (AST → QueryResult)
 * </pre>
 *
 * <p>Each {@code KoraDB} instance maintains its own isolated {@link Catalog}
 * (schema + data). In Milestone 1 this is all in-memory.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   KoraDB db = new KoraDB();
 *
 *   db.execute("CREATE DATABASE finance");
 *   db.execute("USE finance");
 *   db.execute("CREATE TABLE accounts (id INT, name VARCHAR, balance DOUBLE)");
 *   db.execute("INSERT INTO accounts VALUES (1, 'Alice', 5000.0)");
 *
 *   QueryResult result = db.execute("SELECT * FROM accounts WHERE balance > 1000");
 *   System.out.print(result.toDisplayString());
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * <p>Not thread-safe in Milestone 1. Each thread should use its own
 * {@code KoraDB} instance until Phase 7 (transactions) is implemented.
 */
public final class KoraDB implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KoraDB.class);

    private final Catalog           catalog;
    private final StatementExecutor executor;
    private final LockManager       lockManager;
    private final TransactionManager transactionManager;
    private final dev.koradb.session.Session defaultSession;

    /**
     * Creates a new KoraDB instance backed entirely by in-memory storage.
     * Data is lost when this instance is garbage-collected.
     * Suitable for testing, the REPL, and future in-memory mode.
     */
    public KoraDB() {
        this(new InMemoryCatalog());
    }

    /**
     * Creates a KoraDB instance backed by disk storage at the given directory.
     * Data persists across JVM restarts.
     *
     * <p>The data directory is created if it does not exist.
     *
     * @param dataDirectory root directory for all database files
     */
    public KoraDB(Path dataDirectory) {
        this(new DiskCatalog(dataDirectory));
    }

    /**
     * Creates a KoraDB instance with the provided catalog.
     * Useful in tests that need a custom or pre-populated catalog.
     *
     * @param catalog the catalog implementation to use; must not be null
     */
    public KoraDB(Catalog catalog) {
        this.catalog            = catalog;
        this.executor           = new StatementExecutor();
        this.lockManager        = new LockManager();
        this.transactionManager = new TransactionManager(this.lockManager, this.catalog);
        this.defaultSession     = new dev.koradb.session.Session(this.catalog);
        if (this.catalog instanceof DiskCatalog diskCatalog) {
            diskCatalog.setTransactionManager(this.transactionManager);
        }
        log.debug("KoraDB instance initialized ({})",
                catalog.getClass().getSimpleName());
    }

    /**
     * Creates a new isolated client session for concurrent multi-transaction execution.
     */
    public dev.koradb.session.Session createSession() {
        return new dev.koradb.session.Session(catalog);
    }

    /**
     * Returns the default session used for standalone calls to {@link #execute(String)}.
     */
    public dev.koradb.session.Session getDefaultSession() {
        return defaultSession;
    }

    /**
     * Returns the lock manager used by this database instance.
     */
    public LockManager getLockManager() {
        return lockManager;
    }

    /**
     * Returns the transaction manager used by this database instance.
     */
    public TransactionManager getTransactionManager() {
        return transactionManager;
    }

    /**
     * Creates a CheckpointManager bound to the active database and transaction manager.
     * Returns null if the current catalog is in-memory or no database is active.
     */
    public dev.koradb.storage.wal.CheckpointManager createCheckpointManager() {
        if (catalog instanceof DiskCatalog diskCatalog && diskCatalog.getLogManager() != null) {
            return new dev.koradb.storage.wal.CheckpointManager(
                    diskCatalog.getLogManager(),
                    transactionManager,
                    diskCatalog::getOpenTableFiles
            );
        }
        return null;
    }

    /**
     * Executes a checkpoint on the active database and returns the CheckpointEnd LSN.
     *
     * @return LSN of CheckpointEnd record
     */
    public long checkpoint() {
        dev.koradb.storage.wal.CheckpointManager cp = createCheckpointManager();
        if (cp == null) {
            throw new IllegalStateException("Cannot checkpoint: no active disk database or WAL enabled");
        }
        return cp.checkpoint();
    }

    /**
     * Returns the ARIES RecoveryManager for the active database, or null if in-memory or no DB active.
     */
    public dev.koradb.storage.recovery.RecoveryManager getRecoveryManager() {
        if (catalog instanceof DiskCatalog diskCatalog) {
            return diskCatalog.getRecoveryManager();
        }
        return null;
    }

    /**
     * Manually triggers ARIES crash recovery on the active database.
     *
     * @return summary result of the recovery execution
     */
    public dev.koradb.storage.recovery.RecoveryResult recover() {
        dev.koradb.storage.recovery.RecoveryManager rm = getRecoveryManager();
        if (rm == null) {
            throw new IllegalStateException("Cannot recover: no active disk database or WAL enabled");
        }
        return rm.recover();
    }

    /**
     * Creates a VacuumManager to reclaim dead tuple versions across tables.
     */
    public dev.koradb.mvcc.VacuumManager createVacuumManager() {
        return new dev.koradb.mvcc.VacuumManager(catalog, transactionManager);
    }

    /**
     * Reclaims dead tuple versions across all tables in the database.
     *
     * @return the number of dead tuple versions reclaimed
     */
    public int vacuum() {
        return createVacuumManager().vacuum();
    }

    /**
     * Executes a SQL statement on the default session and returns the result.
     *
     * @param sql the SQL string to execute
     * @return the {@link QueryResult}
     * @throws dev.koradb.exception.ParseException     on SQL syntax errors
     * @throws dev.koradb.exception.SchemaException    on schema violations
     * @throws dev.koradb.exception.ExecutionException on runtime execution errors
     */
    public QueryResult execute(String sql) {
        return execute(defaultSession, sql);
    }

    private static volatile java.util.function.Supplier<QueryResult> metricsStatusSupplier;

    public static void setMetricsStatusSupplier(java.util.function.Supplier<QueryResult> supplier) {
        metricsStatusSupplier = supplier;
    }

    /**
     * Executes a SQL statement in the context of the provided session.
     *
     * @param session the client session
     * @param sql     the SQL string to execute
     * @return the {@link QueryResult}
     */
    public QueryResult execute(dev.koradb.session.Session session, String sql) {
        log.debug("execute [session={}]: {}", session.getSessionId(), sql);

        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }

        if (trimmed.equalsIgnoreCase("SHOW DATABASES")) {
            List<String> dbs = session.getCatalog().listDatabases();
            List<Row> rows = new ArrayList<>();
            for (String dbName : dbs) {
                rows.add(new Row(Map.of("database", dbName)));
            }
            return QueryResult.select(rows, List.of("database"));
        }
        if (trimmed.equalsIgnoreCase("SHOW TABLES")) {
            var activeDb = session.getCatalog().currentDatabase();
            if (activeDb.isEmpty()) {
                throw new dev.koradb.exception.SchemaException("No database selected. Use 'USE <dbname>;' first.");
            }
            var database = session.getCatalog().getDatabase(activeDb.get());
            List<Row> rows = new ArrayList<>();
            for (var tbl : database.listTables()) {
                rows.add(new Row(Map.of("table_name", tbl.getName())));
            }
            return QueryResult.select(rows, List.of("table_name"));
        }
        if (trimmed.toUpperCase().startsWith("DESCRIBE ") || trimmed.toUpperCase().startsWith("DESC ")) {
            String tableName = trimmed.substring(trimmed.indexOf(' ') + 1).trim();
            var activeDb = session.getCatalog().currentDatabase();
            if (activeDb.isEmpty()) {
                throw new dev.koradb.exception.SchemaException("No database selected. Use 'USE <dbname>;' first.");
            }
            var database = session.getCatalog().getDatabase(activeDb.get());
            var table = database.getTable(tableName);
            List<Row> rows = new ArrayList<>();
            for (var col : table.getSchema().columns()) {
                rows.add(new Row(Map.of("column_name", col.name(), "data_type", col.dataType().name())));
            }
            return QueryResult.select(rows, List.of("column_name", "data_type"));
        }

        if (trimmed.equalsIgnoreCase("SHOW BUFFER POOL STATUS")) {
            return getBufferPoolStatus();
        }
        if (trimmed.equalsIgnoreCase("SHOW TRANSACTIONS")) {
            return getActiveTransactionsStatus();
        }
        if (trimmed.equalsIgnoreCase("SHOW METRICS")) {
            if (metricsStatusSupplier != null) {
                return metricsStatusSupplier.get();
            }
            return QueryResult.select(List.of(), List.of("name", "type", "value"));
        }

        List<Token> tokens    = new Lexer(sql).tokenize();
        Statement   statement = new Parser(tokens).parse();
        return execute(session, statement);
    }

    private QueryResult getBufferPoolStatus() {
        long hits = 0L;
        long misses = 0L;
        long evictions = 0L;
        long dirtyEvictions = 0L;

        if (catalog instanceof dev.koradb.catalog.DiskCatalog diskCatalog) {
            for (dev.koradb.storage.TableFile tf : diskCatalog.getOpenTableFiles()) {
                if (tf != null && tf.getBufferPool() != null) {
                    dev.koradb.buffer.BufferPoolStats stats = tf.getBufferPool().getStats();
                    if (stats != null) {
                        hits += stats.getHits();
                        misses += stats.getMisses();
                        evictions += stats.getEvictions();
                        dirtyEvictions += stats.getDirtyEvictions();
                    }
                }
            }
        }

        long total = hits + misses;
        double ratio = total == 0L ? 0.0 : (double) hits / total;
        String ratioStr = String.format(java.util.Locale.ROOT, "%.2f%%", ratio * 100.0);

        List<String> cols = List.of("metric", "value");
        List<Row> rows = List.of(
                new Row(Map.of("metric", "hits", "value", String.valueOf(hits))),
                new Row(Map.of("metric", "misses", "value", String.valueOf(misses))),
                new Row(Map.of("metric", "hit_ratio", "value", ratioStr)),
                new Row(Map.of("metric", "evictions", "value", String.valueOf(evictions))),
                new Row(Map.of("metric", "dirty_evictions", "value", String.valueOf(dirtyEvictions)))
        );
        return QueryResult.select(rows, cols);
    }

    private QueryResult getActiveTransactionsStatus() {
        List<String> cols = List.of("txn_id", "state", "isolation_level", "start_time_millis");
        List<Row> rows = new ArrayList<>();
        for (dev.koradb.transaction.Transaction txn : transactionManager.getActiveTransactions()) {
            rows.add(new Row(Map.of(
                    "txn_id", String.valueOf(txn.getId().value()),
                    "state", txn.getState().name(),
                    "isolation_level", txn.getIsolationLevel().name(),
                    "start_time_millis", String.valueOf(txn.getStartTimeMillis())
            )));
        }
        return QueryResult.select(rows, cols);
    }

    /**
     * Executes a parsed SQL statement in the context of the provided session.
     */
    public QueryResult execute(dev.koradb.session.Session session, Statement statement) {
        Catalog cat = session.getCatalog();

        return switch (statement) {
            case dev.koradb.sql.ast.BeginStmt s -> {
                if (session.hasActiveTransaction()) {
                    throw new dev.koradb.exception.ExecutionException("Transaction is already active");
                }
                dev.koradb.transaction.Transaction txn = transactionManager.beginTransaction(session.getDefaultIsolationLevel());
                session.setActiveTransaction(txn);
                yield QueryResult.ddl();
            }
            case dev.koradb.sql.ast.CommitStmt s -> {
                if (!session.hasActiveTransaction()) {
                    throw new dev.koradb.exception.ExecutionException("No active transaction to commit");
                }
                transactionManager.commitTransaction(session.getActiveTransaction());
                session.clearActiveTransaction();
                yield QueryResult.ddl();
            }
            case dev.koradb.sql.ast.RollbackStmt s -> {
                if (!session.hasActiveTransaction()) {
                    throw new dev.koradb.exception.ExecutionException("No active transaction to rollback");
                }
                transactionManager.abortTransaction(session.getActiveTransaction(), cat);
                session.clearActiveTransaction();
                yield QueryResult.ddl();
            }
            default -> {
                if (session.hasActiveTransaction()) {
                    dev.koradb.transaction.Transaction txn = session.getActiveTransaction();
                    try {
                        yield executor.execute(statement, cat, txn, lockManager, transactionManager);
                    } catch (dev.koradb.transaction.lock.DeadlockException de) {
                        transactionManager.abortTransaction(txn, cat);
                        session.clearActiveTransaction();
                        throw de;
                    } catch (dev.koradb.mvcc.WriteConflictException wce) {
                        transactionManager.abortTransaction(txn, cat);
                        session.clearActiveTransaction();
                        throw wce;
                    }
                } else if (session.isAutoCommit()) {
                    dev.koradb.transaction.Transaction txn = transactionManager.beginTransaction(session.getDefaultIsolationLevel());
                    try {
                        QueryResult res = executor.execute(statement, cat, txn, lockManager, transactionManager);
                        transactionManager.commitTransaction(txn);
                        yield res;
                    } catch (Exception e) {
                        transactionManager.abortTransaction(txn, cat);
                        throw e;
                    }
                } else {
                    yield executor.execute(statement, cat);
                }
            }
        };
    }

    /**
     * Returns the catalog used by this instance.
     * Intended for inspection in tests and tooling — not for direct mutation.
     */
    public Catalog getCatalog() {
        return catalog;
    }

    /**
     * Closes this KoraDB instance, releasing all open file handles and rolling back active transactions.
     *
     * <p>For disk-backed instances ({@link dev.koradb.catalog.DiskCatalog}),
     * this closes all open table files cleanly. Calling this method is
     * important before the JVM exits or when testing restart behaviour.
     *
     * <p>In-memory instances (tests, REPL) may also be closed harmlessly.
     */
    @Override
    public void close() {
        if (defaultSession.hasActiveTransaction()) {
            try {
                transactionManager.abortTransaction(defaultSession.getActiveTransaction(), catalog);
            } catch (Exception e) {
                log.warn("Error aborting active transaction during shutdown", e);
            }
        }
        if (catalog instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception e) {
                log.warn("Error closing catalog", e);
            }
        }
    }
}
