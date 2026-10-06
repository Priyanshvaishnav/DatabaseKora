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
 * // 2. End-to-end natural language execution
 * AiQueryResult res = ai.ask("show all users");
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
        DatabaseSchema schema = getActiveSchema();
        return engine.translate(prompt, schema);
    }

    /**
     * Translates and executes a natural language query against the bound database.
     */
    public AiQueryResult ask(String prompt) {
        if (db == null) {
            throw new IllegalStateException("Cannot execute query: KoraAi was created in standalone mode without a bound KoraDB instance.");
        }

        long start = System.currentTimeMillis();
        TranslationResult translation = translate(prompt);
        if (!translation.isValid()) {
            return AiQueryResult.failure(prompt, translation.generatedSql(),
                    "Translation failed validation: " + translation.errorMessage(),
                    System.currentTimeMillis() - start);
        }

        try {
            QueryResult qr = db.execute(translation.generatedSql());
            return AiQueryResult.success(prompt, translation.generatedSql(), qr, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return AiQueryResult.failure(prompt, translation.generatedSql(),
                    "Execution failed: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
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
        if (db == null || db.getCatalog() == null) {
            return null;
        }
        var currentDbOpt = db.getCatalog().currentDatabase();
        if (currentDbOpt.isEmpty()) {
            return null;
        }
        return SchemaExtractor.fromCatalog(db.getCatalog(), currentDbOpt.get());
    }

    @Override
    public void close() throws Exception {
        engine.close();
    }
}
