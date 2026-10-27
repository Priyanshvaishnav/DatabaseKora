package dev.koradb.ai;

import dev.koradb.KoraDB;
import dev.koradb.ai.engine.KoraAiEngine;
import dev.koradb.ai.model.AiQueryResult;
import dev.koradb.ai.model.ExplanationResult;
import dev.koradb.ai.model.OptimizationReport;
import dev.koradb.ai.model.TranslationResult;
import dev.koradb.ai.provider.LlmClient;
import dev.koradb.ai.schema.DatabaseSchema;
import dev.koradb.ai.schema.SchemaExtractor;
import dev.koradb.execution.QueryResult;

import java.util.Objects;

/**
 * High-level developer entry point for all KoraDB AI interactions.
 *
 * <p>Example usage:
 * <pre>{@code
 * KoraDB db = new KoraDB();
 * db.execute("CREATE DATABASE prod");
 * db.execute("USE prod");
 * db.execute("CREATE TABLE users (id INT, name VARCHAR, balance DOUBLE)");
 *
 * KoraAi ai = KoraAi.create(db);
 *
 * // 1. Natural Language to SQL
 * TranslationResult translation = ai.translate("find users where balance > 1000");
 * System.out.println("Generated SQL: " + translation.generatedSql());
 *
 * // 2. End-to-end natural language execution (read-only unless a policy allows more)
 * AiQueryResult res = ai.ask("show all users");
 * AiQueryResult del = ai.ask("delete users where id = 7", AiExecutionPolicy.ALLOW_WRITES);
 *
 * // 3. Explain query
 * ExplanationResult explanation = ai.explain("SELECT * FROM users WHERE id = 42");
 *
 * // 4. Optimization advisor
 * OptimizationReport report = ai.advise("SELECT * FROM users WHERE balance > 500");
 * }</pre>
 */
public final class KoraAi implements AutoCloseable {

    private final KoraDB db;
    private final KoraAiEngine engine;
    private Boolean shareSampleValues;      // null = provider default
    private final java.util.Map<String, SchemaExtractor.CachedSamples> sampleCache = new java.util.concurrent.ConcurrentHashMap<>();

    private KoraAi(KoraDB db, KoraAiEngine engine) {
        this.db = db;
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
    }

    public static KoraAi create(KoraDB db) {
        return new KoraAi(db, new KoraAiEngine());
    }

    public static KoraAi create(KoraDB db, LlmClient client) {
        return new KoraAi(db, new KoraAiEngine(client));
    }

    public static KoraAi create(KoraDB db, String providerName) {
        return new KoraAi(db, KoraAiEngine.withProvider(providerName));
    }

    public static KoraAi standalone() {
        return new KoraAi(null, new KoraAiEngine());
    }

    public static KoraAi standalone(LlmClient client) {
        return new KoraAi(null, new KoraAiEngine(client));
    }

    /**
     * Translates a natural language prompt into a valid KoraDB SQL query.
     */
    public TranslationResult translate(String prompt) {
        DatabaseSchema schema = getActiveSchema(includeSampleValues());
        return engine.translate(prompt, schema);
    }

    /**
     * Whether a few distinct values of short text columns (e.g. {@code status IN ('pending','shipped')})
     * are included in the schema given to the model. They let "orders that are pending" become
     * {@code WHERE status = 'pending'}. On by default for the offline rule-based client; off by default
     * for remote providers, because the values are table data sent to a third party.
     */
    public KoraAi shareSampleValues(boolean share) {
        this.shareSampleValues = share;
        return this;
    }

    private boolean includeSampleValues() {
        return shareSampleValues != null ? shareSampleValues
                : engine.getLlmClient() instanceof dev.koradb.ai.provider.RuleBasedLlmClient;
    }

    /**
     * Translates and executes a natural language query against the bound database, executing
     * read-only statements only ({@link AiExecutionPolicy#READ_ONLY}). A request that translates
     * to INSERT/UPDATE/DELETE or DDL is returned unexecuted with the generated SQL for review.
     */
    public AiQueryResult ask(String prompt) {
        return ask(prompt, AiExecutionPolicy.READ_ONLY);
    }

    /**
     * Translates and executes a natural language query, executing only what {@code policy} allows.
     * Even when allowed, a data-changing statement runs only if the request asks for that kind of
     * change (e.g. a DELETE needs "delete"/"remove" in the request), so an LLM answer that does not
     * match the request (a wrong guess or a prompt injection) is never executed.
     */
    public AiQueryResult ask(String prompt, AiExecutionPolicy policy) {
        if (db == null) {
            throw new IllegalStateException("Cannot execute query: KoraAi was created in standalone mode without a bound KoraDB instance.");
        }
        Objects.requireNonNull(policy, "policy must not be null");

        long start = System.currentTimeMillis();
        TranslationResult translation;
        try {
            translation = engine.translate(prompt, getActiveSchema(includeSampleValues()));
        } catch (RuntimeException e) {
            return AiQueryResult.failure(prompt, null, "Translation failed: " + e.getMessage(), System.currentTimeMillis() - start);
        }
        String sql = translation.generatedSql();
        if (!translation.isValid()) {
            return AiQueryResult.failure(prompt, sql,
                    "Translation failed validation: " + translation.errorMessage(),
                    System.currentTimeMillis() - start);
        }

        String refusal;
        try {
            refusal = executionRefusal(prompt, dev.koradb.ai.engine.SqlSafety.parseSingle(sql), policy);
        } catch (RuntimeException e) {
            refusal = "Generated SQL could not be parsed: " + e.getMessage();
        }
        if (refusal != null) {
            return AiQueryResult.failure(prompt, sql, refusal, System.currentTimeMillis() - start);
        }

        try {
            QueryResult qr = db.execute(sql);
            return AiQueryResult.success(prompt, sql, qr, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return AiQueryResult.failure(prompt, sql,
                    "Execution failed: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    /** Why {@code stmt} must not be executed for {@code prompt} under {@code policy}, or null. */
    static String executionRefusal(String prompt, dev.koradb.sql.ast.Statement stmt, AiExecutionPolicy policy) {
        var kind = dev.koradb.ai.engine.SqlSafety.classify(stmt);
        String verb = dev.koradb.ai.engine.SqlSafety.verb(stmt);
        switch (kind) {
            case READ:
                return null;
            case TRANSACTION:
                return verb + " was not executed: ask() runs single statements and does not manage transactions.";
            case WRITE:
                if (policy == AiExecutionPolicy.READ_ONLY) {
                    return verb + " was not executed: ask() is read-only by default. Review the SQL and run it yourself, "
                            + "or call ask(prompt, AiExecutionPolicy.ALLOW_WRITES).";
                }
                break;
            case DDL:
                if (policy != AiExecutionPolicy.ALLOW_ALL) {
                    return verb + " was not executed: schema changes need ask(prompt, AiExecutionPolicy.ALLOW_ALL). "
                            + "Review the SQL and run it yourself.";
                }
                break;
        }
        if (!requestAsksFor(prompt, stmt)) {
            return verb + " was not executed: the request does not ask for this change, so the generated statement "
                    + "does not match it.";
        }
        return null;
    }

    private static boolean requestAsksFor(String prompt, dev.koradb.sql.ast.Statement stmt) {
        java.util.Set<String> words = new java.util.HashSet<>(java.util.Arrays.asList(
                prompt.toLowerCase(java.util.Locale.ROOT).split("[^a-z]+")));
        java.util.List<String> needed = switch (stmt) {
            case dev.koradb.sql.ast.DeleteStmt s -> java.util.List.of("delete", "remove", "erase", "purge", "clear");
            case dev.koradb.sql.ast.TruncateStmt s -> java.util.List.of("truncate", "delete", "remove", "empty", "clear", "purge");
            case dev.koradb.sql.ast.AlterTableStmt s -> java.util.List.of("alter", "add", "column");
            case dev.koradb.sql.ast.UpdateStmt s -> java.util.List.of("update", "set", "change", "modify", "mark", "rename");
            case dev.koradb.sql.ast.InsertStmt s -> java.util.List.of("insert", "add", "create", "record", "register", "new");
            case dev.koradb.sql.ast.DropTableStmt s -> java.util.List.of("drop", "delete", "remove");
            case dev.koradb.sql.ast.DropIndexStmt s -> java.util.List.of("drop", "delete", "remove");
            case dev.koradb.sql.ast.DropDatabaseStmt s -> java.util.List.of("drop", "delete", "remove");
            case dev.koradb.sql.ast.UseDatabaseStmt s -> java.util.List.of("use", "switch");
            default -> java.util.List.of("create", "add", "make", "new", "build");
        };
        return needed.stream().anyMatch(words::contains);
    }

    /**
     * Explains a SQL query in plain English.
     */
    public ExplanationResult explain(String sql) {
        DatabaseSchema schema = getActiveSchema();
        return engine.explain(sql, schema);
    }

    /**
     * Analyzes query performance and provides missing index recommendations.
     */
    public OptimizationReport advise(String sql) {
        DatabaseSchema schema = getActiveSchema();
        return engine.advise(sql, schema);
    }

    public KoraAiEngine getEngine() {
        return engine;
    }

    private DatabaseSchema getActiveSchema() {
        return getActiveSchema(false);
    }

    private DatabaseSchema getActiveSchema(boolean withSamples) {
        if (db == null || db.getCatalog() == null) {
            return null;
        }
        var currentDbOpt = db.getCatalog().currentDatabase();
        if (currentDbOpt.isEmpty()) {
            return null;
        }
        DatabaseSchema schema = SchemaExtractor.fromCatalog(db.getCatalog(), currentDbOpt.get());
        return withSamples ? SchemaExtractor.withSampleValues(schema, db, sampleCache) : schema;
    }

    @Override
    public void close() throws Exception {
        engine.close();
    }
}
