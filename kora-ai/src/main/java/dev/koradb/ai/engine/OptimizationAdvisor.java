package dev.koradb.ai.engine;

import dev.koradb.ai.model.OptimizationReport;
import dev.koradb.ai.prompt.PromptTemplates;
import dev.koradb.ai.provider.LlmClient;
import dev.koradb.ai.provider.LlmRequest;
import dev.koradb.ai.provider.LlmResponse;
import dev.koradb.ai.schema.DatabaseSchema;
import dev.koradb.ai.schema.TableSchema;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates queries against active database schemas to detect sequential table scan risks
 * and suggest high-impact B+ Tree index creations and plan optimizations.
 */
public final class OptimizationAdvisor {

    private static final Pattern WHERE_PREDICATE = Pattern.compile(
            "WHERE\\s+([a-zA-Z0-9_]+)\\s*(?:=|>|<|>=|<=)", Pattern.CASE_INSENSITIVE
    );

    private static final Pattern FROM_TABLE = Pattern.compile(
            "FROM\\s+([a-zA-Z0-9_]+)", Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ORDER_BY_COL = Pattern.compile(
            "ORDER\\s+BY\\s+([a-zA-Z0-9_]+)", Pattern.CASE_INSENSITIVE
    );

    private final LlmClient llmClient;

    public OptimizationAdvisor(LlmClient llmClient) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
    }

    /**
     * Inspects the query, checks existing indexes in the schema, and generates index recommendations.
     */
    public OptimizationReport advise(String sql, DatabaseSchema schema) {
        Objects.requireNonNull(sql, "sql must not be null");

        long start = System.currentTimeMillis();
        List<String> recommendedDdls = new ArrayList<>();

        // 1. Rule-based static schema inspection
        String tableName = extractTableName(sql);
        TableSchema tableSchema = (schema != null && tableName != null) ? schema.getTable(tableName) : null;

        // Check WHERE predicates
        Matcher whereMatcher = WHERE_PREDICATE.matcher(sql);
        if (whereMatcher.find()) {
            String col = whereMatcher.group(1);
            if (tableSchema == null || !tableSchema.hasIndexOn(col)) {
                String ddl = String.format("CREATE INDEX idx_%s_%s ON %s (%s);",
                        tableName != null ? tableName.toLowerCase() : "table",
                        col.toLowerCase(),
                        tableName != null ? tableName : "table",
                        col);
                recommendedDdls.add(ddl);
            }
        }

        // Check ORDER BY columns
        Matcher orderMatcher = ORDER_BY_COL.matcher(sql);
        if (orderMatcher.find()) {
            String orderCol = orderMatcher.group(1);
            if (tableSchema == null || !tableSchema.hasIndexOn(orderCol)) {
                String ddl = String.format("CREATE INDEX idx_%s_%s ON %s (%s);",
                        tableName != null ? tableName.toLowerCase() : "table",
                        orderCol.toLowerCase(),
                        tableName != null ? tableName : "table",
                        orderCol);
                if (!recommendedDdls.contains(ddl)) {
                    recommendedDdls.add(ddl);
                }
            }
        }

        // 2. Query LLM for detailed narrative and analysis
        String prompt = PromptTemplates.buildOptimizationPrompt(sql, schema);
        LlmResponse response = llmClient.complete(LlmRequest.of(prompt));

        // 3. Extract any additional CREATE INDEX ddls mentioned in the LLM response
        Pattern ddlPattern = Pattern.compile("CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+[^;]+;", Pattern.CASE_INSENSITIVE);
        Matcher ddlMatcher = ddlPattern.matcher(response.content());
        while (ddlMatcher.find()) {
            String matched = ddlMatcher.group().trim();
            Matcher colMatcher = Pattern.compile("\\(([^)]+)\\)").matcher(matched);
            boolean alreadyIndexed = false;
            if (colMatcher.find() && tableSchema != null) {
                String c = colMatcher.group(1).trim();
                if (tableSchema.hasIndexOn(c)) {
                    alreadyIndexed = true;
                }
            }
            if (!alreadyIndexed && !recommendedDdls.contains(matched)) {
                recommendedDdls.add(matched);
            }
        }

        long duration = System.currentTimeMillis() - start;
        return new OptimizationReport(
                sql.trim(),
                response.content().trim(),
                recommendedDdls,
                llmClient.getProviderName(),
                llmClient.getModelName(),
                duration
        );
    }

    private static String extractTableName(String sql) {
        Matcher m = FROM_TABLE.matcher(sql);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
