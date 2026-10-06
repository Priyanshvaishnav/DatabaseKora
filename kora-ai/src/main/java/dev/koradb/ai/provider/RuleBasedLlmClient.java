package dev.koradb.ai.provider;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic offline rule-based NLP matcher for Text-to-SQL and query understanding.
 *
 * <p>Requires zero network access, zero external credentials, and provides instant,
 * repeatable translations for common conversational query patterns.
 */
public final class RuleBasedLlmClient implements LlmClient {

    public static final String PROVIDER_NAME = "rule-based";
    public static final String MODEL_NAME = "offline-rules-v1";

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public String getModelName() {
        return MODEL_NAME;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long start = System.currentTimeMillis();
        String user = request.userPrompt() != null ? request.userPrompt().trim() : "";
        String system = request.systemPrompt() != null ? request.systemPrompt() : "";

        // 1. Check if this is an optimization advice prompt
        if (user.contains("Analyze the following query and provide concrete index recommendations")
                || user.contains("Performance Bottleneck Analysis")) {
            String advice = handleOptimizationAdvice(user);
            return LlmResponse.of(advice, MODEL_NAME, PROVIDER_NAME, System.currentTimeMillis() - start);
        }

        // 2. Check if this is an explanation prompt
        if (user.contains("Explain the following") || user.contains("query analyst")) {
            String explanation = handleExplanation(user);
            return LlmResponse.of(explanation, MODEL_NAME, PROVIDER_NAME, System.currentTimeMillis() - start);
        }

        // 3. Otherwise handle Text-to-SQL translation
        String rawQuery = user.replace("Translate the following request into a valid KoraDB SQL query:", "").trim();
        String sql = translateTextToSql(rawQuery);
        String formattedResponse = "```sql\n" + sql + "\n```";

        return LlmResponse.of(formattedResponse, MODEL_NAME, PROVIDER_NAME, System.currentTimeMillis() - start);
    }

    public String translateTextToSql(String text) {
        String cleaned = text.trim();
        if (cleaned.endsWith("?")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }

        // Pattern: "delete from <table_name> where <col> = <val>"
        Matcher mDel = Pattern.compile("^delete\\s+(?:from\\s+)?([a-zA-Z0-9_]+)\\s+where\\s+([a-zA-Z0-9_]+)\\s*(=|>|<)\\s*([a-zA-Z0-9_'.\\-]+)$", Pattern.CASE_INSENSITIVE).matcher(cleaned);
        if (mDel.find()) {
            String table = mDel.group(1);
            String col = mDel.group(2);
            String op = mDel.group(3);
            String val = mDel.group(4);
            if (!val.startsWith("'") && !val.matches("^-?\\d+(\\.\\d+)?$") && !val.equalsIgnoreCase("true") && !val.equalsIgnoreCase("false")) {
                val = "'" + val + "'";
            }
            return "DELETE FROM " + table + " WHERE " + col + " " + op + " " + val + ";";
        }

        // Pattern: "count <table_name>" or "how many <table_name> [are there]"
        Matcher mCount = Pattern.compile("^(?:how many|count|number of)\\s+([a-zA-Z0-9_]+)(?:\\s+are there)?$", Pattern.CASE_INSENSITIVE).matcher(cleaned);
        if (mCount.find()) {
            String table = mCount.group(1);
            return "SELECT COUNT(*) FROM " + table + ";";
        }

        // Pattern: "find <table_name> where <col> <op> <val>"
        Matcher mWhere = Pattern.compile("^(?:find|get|show|select|fetch)(?:\\s+all)?\\s+(?:from\\s+)?([a-zA-Z0-9_]+)\\s+where\\s+([a-zA-Z0-9_]+)\\s*(=|>|<|>=|<=|is|equals)\\s*([a-zA-Z0-9_'.\\-]+)$", Pattern.CASE_INSENSITIVE).matcher(cleaned);
        if (mWhere.find()) {
            String table = mWhere.group(1);
            String col = mWhere.group(2);
            String op = mWhere.group(3);
            String val = mWhere.group(4);
            if (op.equalsIgnoreCase("is") || op.equalsIgnoreCase("equals")) op = "=";
            if (!val.startsWith("'") && !val.matches("^-?\\d+(\\.\\d+)?$") && !val.equalsIgnoreCase("true") && !val.equalsIgnoreCase("false")) {
                val = "'" + val + "'";
            }
            return "SELECT * FROM " + table + " WHERE " + col + " " + op + " " + val + ";";
        }

        String lower = cleaned.toLowerCase(Locale.ROOT);

        // Pattern: Already valid SQL statement
        if (lower.startsWith("select ") || lower.startsWith("insert into ") ||
            lower.startsWith("update ") || lower.startsWith("delete from ") ||
            lower.startsWith("create table ") || lower.startsWith("create index ")) {
            return cleaned.endsWith(";") ? cleaned : cleaned + ";";
        }

        // Pattern: "top <N> <table_name> by <col> [desc/asc]"
        Matcher mTop = Pattern.compile("^(?:show\\s+|find\\s+|get\\s+)?top\\s+(\\d+)\\s+([a-zA-Z0-9_]+)\\s+by\\s+([a-zA-Z0-9_]+)(?:\\s+(asc|desc))?$").matcher(lower);
        if (mTop.find()) {
            int limit = Integer.parseInt(mTop.group(1));
            String table = mTop.group(2);
            String col = mTop.group(3);
            String dir = "desc".equalsIgnoreCase(mTop.group(4)) || mTop.group(4) == null ? "DESC" : "ASC";
            return "SELECT * FROM " + table + " ORDER BY " + col + " " + dir + " LIMIT " + limit + ";";
        }

        // Pattern: "average/sum/min/max <col> in/from <table_name>"
        Matcher mAgg = Pattern.compile("^(?:calculate\\s+|compute\\s+|find\\s+)?(avg|average|sum|total|min|minimum|max|maximum)\\s+([a-zA-Z0-9_]+)\\s+(?:in|from|of)\\s+([a-zA-Z0-9_]+)$").matcher(lower);
        if (mAgg.find()) {
            String fnName = switch (mAgg.group(1)) {
                case "avg", "average" -> "AVG";
                case "sum", "total" -> "SUM";
                case "min", "minimum" -> "MIN";
                case "max", "maximum" -> "MAX";
                default -> "COUNT";
            };
            String col = mAgg.group(2);
            String table = mAgg.group(3);
            return "SELECT " + fnName + "(" + col + ") FROM " + table + ";";
        }

        // Pattern: "group <table_name> by <col>" or "count <table_name> by <col>"
        Matcher mGroup = Pattern.compile("^(?:group|count)\\s+([a-zA-Z0-9_]+)\\s+by\\s+([a-zA-Z0-9_]+)$").matcher(lower);
        if (mGroup.find()) {
            String table = mGroup.group(1);
            String col = mGroup.group(2);
            return "SELECT " + col + ", COUNT(*) FROM " + table + " GROUP BY " + col + ";";
        }

        // Pattern: "show all <table_name>" / "list all <table_name>" / "get all <table_name>"
        Matcher mAll = Pattern.compile("^(?:show|list|get|fetch|select)(?:\\s+all)?\\s+(?:from\\s+)?([a-zA-Z0-9_]+)$").matcher(lower);
        if (mAll.find()) {
            String table = mAll.group(1);
            return "SELECT * FROM " + table + ";";
        }

        // Fallback default: select all from text assuming text was table name
        String sanitized = lower.replaceAll("[^a-zA-Z0-9_]", "");
        if (!sanitized.isEmpty()) {
            return "SELECT * FROM " + sanitized + ";";
        }

        return "SELECT * FROM dummy;";
    }

    private String handleExplanation(String userPrompt) {
        String sql = extractSqlFromPrompt(userPrompt);
        StringBuilder sb = new StringBuilder();
        sb.append("### Query Explanation\n\n");
        sb.append("**Intent:** ");
        if (sql.toUpperCase().startsWith("SELECT")) {
            sb.append("Retrieves matching records from the database.\n\n");
            sb.append("**Execution Steps:**\n");
            sb.append("1. **Scan**: Evaluates rows from target relation(s).\n");
            if (sql.toUpperCase().contains("WHERE")) {
                sb.append("2. **Filter Predicate**: Applies WHERE filter condition(s) to discard non-matching rows.\n");
            }
            if (sql.toUpperCase().contains("JOIN")) {
                sb.append("3. **Join Operator**: Computes equi-join on specified key attributes.\n");
            }
            if (sql.toUpperCase().contains("GROUP BY")) {
                sb.append("4. **Aggregation**: Groups records and evaluates aggregate expressions.\n");
            }
            if (sql.toUpperCase().contains("ORDER BY")) {
                sb.append("5. **Sorting & Projection**: Sorts results and projects specified output columns.\n");
            }
        } else if (sql.toUpperCase().startsWith("INSERT")) {
            sb.append("Inserts a new record into the specified table.\n");
        } else if (sql.toUpperCase().startsWith("UPDATE")) {
            sb.append("Updates column values for rows matching the WHERE criteria.\n");
        } else if (sql.toUpperCase().startsWith("DELETE")) {
            sb.append("Removes rows satisfying the predicate from the table.\n");
        } else {
            sb.append("Executes DDL/statement against the active database schema.\n");
        }
        return sb.toString();
    }

    private String handleOptimizationAdvice(String userPrompt) {
        String sql = extractSqlFromPrompt(userPrompt);
        StringBuilder sb = new StringBuilder();
        sb.append("### Performance & Index Recommendations\n\n");

        Pattern wherePattern = Pattern.compile("WHERE\\s+([a-zA-Z0-9_]+)\\s*(=|>|<|>=|<=)", Pattern.CASE_INSENSITIVE);
        Matcher m = wherePattern.matcher(sql);
        if (m.find()) {
            String col = m.group(1);
            String table = extractTableName(sql);
            boolean alreadyIndexed = userPrompt.toLowerCase().contains("(" + col.toLowerCase() + ")");
            if (!alreadyIndexed) {
                sb.append("**Bottleneck:** Filter condition on column `").append(col)
                  .append("` may trigger a full table scan without an index.\n\n");
                sb.append("**Recommendation:** Create a B+ Tree index on `").append(col).append("`:\n");
                sb.append("```sql\nCREATE INDEX idx_").append(table.toLowerCase()).append("_")
                  .append(col.toLowerCase()).append(" ON ")
                  .append(table).append(" (").append(col).append(");\n```\n\n");
                sb.append("**Expected Gain:** Replaces O(N) sequential table scan with O(log N) B+ Tree point lookup or range scan.");
            } else {
                sb.append("Column `").append(col).append("` already has an index defined. Query execution can leverage index scan.");
            }
        } else {
            sb.append("Query does not contain an unindexed filter predicate. Execution plan is optimal.");
        }
        return sb.toString();
    }

    private String extractSqlFromPrompt(String prompt) {
        Pattern p = Pattern.compile("SQL Query:\\s*([^\\n]+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(prompt);
        if (m.find()) {
            return m.group(1).trim();
        }
        return prompt.trim();
    }

    private String extractTableName(String sql) {
        Pattern p = Pattern.compile("FROM\\s+([a-zA-Z0-9_]+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(sql);
        if (m.find()) {
            return m.group(1);
        }
        return "target_table";
    }
}
