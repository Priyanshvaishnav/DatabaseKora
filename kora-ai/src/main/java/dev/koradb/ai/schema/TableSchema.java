package dev.koradb.ai.schema;

import dev.koradb.sql.ast.ColumnDef;
import dev.koradb.sql.ast.DataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Representation of a table schema with its column definitions and associated indexes.
 */
public record TableSchema(
        String tableName,
        List<ColumnDef> columns,
        List<IndexInfo> indexes
) {

    public record IndexInfo(String name, String columnName, DataType keyType, boolean unique) {
        public String toDdl(String tableName) {
            return String.format("CREATE %sINDEX %s ON %s (%s);",
                    unique ? "UNIQUE " : "", name, tableName, columnName);
        }
    }

    public TableSchema {
        columns = List.copyOf(columns);
        indexes = indexes != null ? List.copyOf(indexes) : List.of();
    }

    public boolean hasColumn(String colName) {
        return columns.stream().anyMatch(c -> c.name().equalsIgnoreCase(colName));
    }

    public ColumnDef findColumn(String colName) {
        return columns.stream()
                .filter(c -> c.name().equalsIgnoreCase(colName))
                .findFirst()
                .orElse(null);
    }

    public boolean hasIndexOn(String colName) {
        return indexes.stream().anyMatch(idx -> idx.columnName().equalsIgnoreCase(colName));
    }

    /**
     * Formats the table and its indexes as clean DDL.
     */
    public String toDdl() {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(tableName).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            ColumnDef col = columns.get(i);
            sb.append(col.name()).append(" ").append(col.dataType().name());
        }
        sb.append(");");

        for (IndexInfo idx : indexes) {
            sb.append("\n").append(idx.toDdl(tableName));
        }
        return sb.toString();
    }
}
