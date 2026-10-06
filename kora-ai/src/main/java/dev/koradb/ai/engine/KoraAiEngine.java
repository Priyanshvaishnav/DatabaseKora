package dev.koradb.ai.engine;

import dev.koradb.ai.model.ExplanationResult;
import dev.koradb.ai.model.OptimizationReport;
import dev.koradb.ai.model.TranslationResult;
import dev.koradb.ai.provider.LlmClient;
import dev.koradb.ai.provider.LlmClientFactory;
import dev.koradb.ai.schema.DatabaseSchema;

import java.util.Objects;

/**
 * Main coordinator and engine facade for all KoraDB AI operations:
 * Text-to-SQL translation, query explanation, and performance optimization advice.
 */
public final class KoraAiEngine implements AutoCloseable {

    private final LlmClient llmClient;
    private final SqlGenerator sqlGenerator;
    private final QueryExplainer queryExplainer;
    private final OptimizationAdvisor optimizationAdvisor;

    public KoraAiEngine() {
        this(LlmClientFactory.createDefault());
    }

    public KoraAiEngine(LlmClient llmClient) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        this.sqlGenerator = new SqlGenerator(llmClient);
        this.queryExplainer = new QueryExplainer(llmClient);
        this.optimizationAdvisor = new OptimizationAdvisor(llmClient);
    }

    public static KoraAiEngine withProvider(String providerName) {
        return new KoraAiEngine(LlmClientFactory.createByName(providerName));
    }

    public TranslationResult translate(String prompt, DatabaseSchema schema) {
        return sqlGenerator.generate(prompt, schema);
    }

    public ExplanationResult explain(String sql, DatabaseSchema schema) {
        return queryExplainer.explain(sql, schema);
    }

    public OptimizationReport advise(String sql, DatabaseSchema schema) {
        return optimizationAdvisor.advise(sql, schema);
    }

    public LlmClient getLlmClient() {
        return llmClient;
    }

    public String getActiveProvider() {
        return llmClient.getProviderName();
    }

    public String getActiveModel() {
        return llmClient.getModelName();
    }

    @Override
    public void close() throws Exception {
        llmClient.close();
    }
}
