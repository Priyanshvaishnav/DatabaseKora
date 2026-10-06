package dev.koradb.ai.engine;

import dev.koradb.ai.model.ExplanationResult;
import dev.koradb.ai.prompt.PromptTemplates;
import dev.koradb.ai.provider.LlmClient;
import dev.koradb.ai.provider.LlmRequest;
import dev.koradb.ai.provider.LlmResponse;
import dev.koradb.ai.schema.DatabaseSchema;

import java.util.Objects;

/**
 * Translates SQL statements into plain-English explanations of data access and operations.
 */
public final class QueryExplainer {

    private final LlmClient llmClient;

    public QueryExplainer(LlmClient llmClient) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
    }

    /**
     * Explains the given SQL query within the provided schema context.
     */
    public ExplanationResult explain(String sql, DatabaseSchema schema) {
        Objects.requireNonNull(sql, "sql must not be null");

        String prompt = PromptTemplates.buildQueryExplanationPrompt(sql, schema);
        long start = System.currentTimeMillis();

        LlmResponse response = llmClient.complete(LlmRequest.of(prompt));
        long duration = System.currentTimeMillis() - start;

        return new ExplanationResult(
                sql.trim(),
                response.content().trim(),
                llmClient.getProviderName(),
                llmClient.getModelName(),
                duration
        );
    }
}
