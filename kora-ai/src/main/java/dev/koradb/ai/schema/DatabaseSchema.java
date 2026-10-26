package dev.koradb.ai.schema;

import java.util.*;

/**
 * Representation of an entire database schema with all tables, columns, and indexes.
 */
public record DatabaseSchema(
        String databaseName,
        Map<String, TableSchema> tables,
        Map<String, List<String>> sampleValues
) {

    /**
     * @param sampleValues optional distinct values of short text columns, keyed {@code "table.column"}
     *                     (lower case); empty when not collected
     */
    public DatabaseSchema {
        tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        sampleValues = sampleValues == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(sampleValues));
    }

    public DatabaseSchema(String databaseName, Map<String, TableSchema> tables) {
        this(databaseName, tables, Map.of());
    }

    /** Known values of {@code table.column}, or an empty list. */
    public List<String> valuesOf(String table, String column) {
        return sampleValues.getOrDefault((table + "." + column).toLowerCase(), List.of());
    }

    public TableSchema getTable(String tableName) {
        if (tableName == null) return null;
        return tables.get(tableName.toLowerCase());
    }

    public boolean hasTable(String tableName) {
        if (tableName == null) return false;
        return tables.containsKey(tableName.toLowerCase());
    }

    public List<TableSchema> listTables() {
        return List.copyOf(tables.values());
    }

    /**
     * Formats all tables and indexes in this database as structured DDL context
     * suitable for LLM system prompts.
     */
    public String toPromptContext() {
        if (tables.isEmpty()) {
            return "-- Database: " + databaseName + " (no tables exist)\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("-- Database Schema: ").append(databaseName).append("\n");
        for (TableSchema ts : tables.values()) {
            sb.append(ts.toDdl()).append("\n");
            for (var col : ts.columns()) {
                List<String> vals = valuesOf(ts.tableName(), col.name());
                if (!vals.isEmpty()) {
                    sb.append("-- values of ").append(ts.tableName()).append('.').append(col.name()).append(": ");
                    for (int i = 0; i < vals.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append('\'').append(vals.get(i).replace("'", "''")).append('\'');
                    }
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
