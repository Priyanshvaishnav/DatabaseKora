package dev.koradb.ai.schema;

import java.util.*;

/**
 * Representation of an entire database schema with all tables, columns, and indexes.
 */
public record DatabaseSchema(
        String databaseName,
        Map<String, TableSchema> tables
) {

    public DatabaseSchema {
        tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
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
            sb.append(ts.toDdl()).append("\n\n");
        }
        return sb.toString().trim();
    }
}
