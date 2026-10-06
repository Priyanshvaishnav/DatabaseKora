package dev.koradb.ai.engine;

import dev.koradb.ai.model.TranslationResult;
import dev.koradb.ai.prompt.PromptTemplates;
import dev.koradb.ai.provider.LlmClient;
import dev.koradb.ai.provider.LlmRequest;
import dev.koradb.ai.provider.LlmResponse;
import dev.koradb.ai.provider.RuleBasedLlmClient;
import dev.koradb.ai.schema.DatabaseSchema;
import dev.koradb.sql.lexer.Lexer;
import dev.koradb.sql.lexer.Token;
import dev.koradb.sql.parser.Parser;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates natural language requests into syntax-validated KoraDB SQL statements
 * with automated schema injection and 1-shot self-healing parse verification.
 */
public final class SqlGenerator {

    private static final Pattern SQL_BLOCK_PATTERN = Pattern.compile(
            "```(?:sql)?\\s*([\\s\\S]*?)\\s*```", Pattern.CASE_INSENSITIVE
    );

    private final LlmClient llmClient;

    public SqlGenerator(LlmClient llmClient) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
    }

    /**
     * Translates a natural language prompt into a valid KoraDB SQL statement.
     */
    public TranslationResult generate(String prompt, DatabaseSchema schema) {
        Objects.requireNonNull(prompt, "prompt must not be null");

        String systemPrompt = PromptTemplates.buildSqlGenerationSystemPrompt(schema);
        String userPrompt = PromptTemplates.buildSqlGenerationUserPrompt(prompt);

        long start = System.currentTimeMillis();
        LlmResponse response = llmClient.complete(LlmRequest.of(systemPrompt, userPrompt));
        String rawContent = response.content();
        String sql = extractSql(rawContent);

        // Validation against KoraDB parser
        String parseError = validateSql(sql);

        // Self-healing attempt if parsing failed and using an external LLM
        if (parseError != null && !(llmClient instanceof RuleBasedLlmClient)) {
            String correctionPrompt = PromptTemplates.buildSqlCorrectionPrompt(prompt, sql, parseError);
            LlmResponse retryResponse = llmClient.complete(LlmRequest.of(systemPrompt, correctionPrompt));
            String retrySql = extractSql(retryResponse.content());
            String retryError = validateSql(retrySql);

            if (retryError == null) {
                long duration = System.currentTimeMillis() - start;
                return TranslationResult.valid(prompt, retrySql, llmClient.getProviderName(), llmClient.getModelName(), duration);
            } else {
                sql = retrySql;
                parseError = retryError;
            }
        }

        long duration = System.currentTimeMillis() - start;
        if (parseError == null) {
            return TranslationResult.valid(prompt, sql, llmClient.getProviderName(), llmClient.getModelName(), duration);
        } else {
            return TranslationResult.invalid(prompt, sql, parseError, llmClient.getProviderName(), llmClient.getModelName(), duration);
        }
    }

    /**
     * Extracts pure SQL string from potential Markdown code fences or raw LLM output.
     */
    public static String extractSql(String text) {
        if (text == null || text.isBlank()) return "";

        Matcher matcher = SQL_BLOCK_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            int end = cleaned.lastIndexOf("```");
            if (end > 3) {
                cleaned = cleaned.substring(3, end).trim();
                if (cleaned.toLowerCase().startsWith("sql")) {
                    cleaned = cleaned.substring(3).trim();
                }
            }
        }
        return cleaned;
    }

    /**
     * Validates whether the given SQL string can be successfully tokenized and parsed by KoraDB.
     *
     * @return null if valid, or the error message if invalid
     */
    public static String validateSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return "SQL statement is empty";
        }
        try {
            List<Token> tokens = new Lexer(sql).tokenize();
            new Parser(tokens).parse();
            return null; // Valid
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
