package dev.koradb.ai.prompt;

import dev.koradb.ai.schema.DatabaseSchema;

/**
 * System and user prompt builders for Text-to-SQL, query explanation, and optimization suggestions.
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    public static String buildSqlGenerationSystemPrompt(DatabaseSchema schema) {
        String schemaContext = (schema != null) ? schema.toPromptContext() : "-- No schema provided";

        return String.format("""
                You are an expert AI database assistant specifically built for KoraDB, a relational database engine.
                Your task is to translate natural language user requests into precise, valid KoraDB SQL statements.

                %s

                Active Database Schema:
                %s

                Instructions:
                - Return ONLY the executable SQL query inside a markdown code block: ```sql ... ```.
                - Do not include conversational filler, preamble, or apologies.
                - Follow all KoraDB syntax rules strictly (no table aliases, single quotes for strings, semicolons).
                """, DialectRules.KORADB_SQL_GUIDELINES, schemaContext);
    }

    public static String buildSqlGenerationUserPrompt(String naturalLanguagePrompt) {
        return "Translate the following request into a valid KoraDB SQL query:\n" + naturalLanguagePrompt.trim();
    }

    public static String buildSqlCorrectionPrompt(String naturalLanguagePrompt, String failedSql, String errorMessage) {
        return String.format("""
                The SQL statement you generated failed to parse in KoraDB.
                
                User Request: %s
                Generated SQL: %s
                Parser Error: %s

                Please correct the SQL query to strictly adhere to KoraDB's grammar rules and return ONLY the corrected SQL in a ```sql ... ``` block.
                """, naturalLanguagePrompt, failedSql, errorMessage);
    }

    public static String buildQueryExplanationPrompt(String sql, DatabaseSchema schema) {
        String schemaContext = (schema != null) ? schema.toPromptContext() : "";
        return String.format("""
                You are an expert database query analyst.
                Explain the following KoraDB SQL query in plain, easy-to-understand English.

                Active Schema:
                %s

                SQL Query:
                %s

                Your explanation should provide:
                1. A one-sentence summary of the business intent.
                2. Step-by-step breakdown of tables accessed, filters applied, joins, aggregations, and sort order.
                3. Expected result structure (columns returned).
                """, schemaContext, sql.trim());
    }

    public static String buildOptimizationPrompt(String sql, DatabaseSchema schema) {
        String schemaContext = (schema != null) ? schema.toPromptContext() : "";
        return String.format("""
                You are an expert database performance engineer and query optimizer for KoraDB.
                Analyze the following query and provide concrete index recommendations and performance advice.

                Active Schema & Existing Indexes:
                %s

                SQL Query:
                %s

                Provide:
                1. Performance Bottleneck Analysis (e.g. Sequential Table Scans vs Index Lookups).
                2. Recommended Index DDL statements: CREATE [UNIQUE] INDEX idx_name ON table (column);
                3. Expected performance improvement rationale.
                """, schemaContext, sql.trim());
    }
}
