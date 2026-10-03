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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

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

    private final Catalog          catalog;
    private final StatementExecutor executor;

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
        this.catalog  = catalog;
        this.executor = new StatementExecutor();
        log.debug("KoraDB instance initialized ({})",
                catalog.getClass().getSimpleName());
    }

    /**
     * Executes a SQL statement and returns the result.
     *
     * @param sql the SQL string to execute
     * @return the {@link QueryResult}
     * @throws dev.koradb.exception.ParseException     on SQL syntax errors
     * @throws dev.koradb.exception.SchemaException    on schema violations
     * @throws dev.koradb.exception.ExecutionException on runtime execution errors
     */
    public QueryResult execute(String sql) {
        log.debug("execute: {}", sql);

        List<Token> tokens    = new Lexer(sql).tokenize();
        Statement   statement = new Parser(tokens).parse();
        return executor.execute(statement, catalog);
    }

    /**
     * Returns the catalog used by this instance.
     * Intended for inspection in tests and tooling — not for direct mutation.
     */
    public Catalog getCatalog() {
        return catalog;
    }

    /**
     * Closes this KoraDB instance, releasing all open file handles.
     *
     * <p>For disk-backed instances ({@link dev.koradb.catalog.DiskCatalog}),
     * this closes all open table files cleanly. Calling this method is
     * important before the JVM exits or when testing restart behaviour.
     *
     * <p>In-memory instances (tests, REPL) may also be closed harmlessly.
     */
    @Override
    public void close() {
        if (catalog instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception e) {
                log.warn("Error closing catalog", e);
            }
        }
    }
}
