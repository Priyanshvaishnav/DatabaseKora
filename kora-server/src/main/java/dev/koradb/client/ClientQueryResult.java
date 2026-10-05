package dev.koradb.client;

import java.util.Collections;
import java.util.List;

/**
 * Result returned to client applications after executing a SQL query or command.
 *
 * @param isQuery         true if query returned a tabular result set (e.g. SELECT)
 * @param affectedRows    number of rows affected by INSERT, UPDATE, DELETE
 * @param message         status or informational message
 * @param columns         list of column names
 * @param rows            list of rows containing typed values
 * @param executionTimeMs server-reported or round-trip execution latency in milliseconds
 */
public record ClientQueryResult(
        boolean isQuery,
        int affectedRows,
        String message,
        List<String> columns,
        List<List<Object>> rows,
        long executionTimeMs
) {

    public ClientQueryResult {
        columns = columns != null ? List.copyOf(columns) : Collections.emptyList();
        rows = rows != null ? List.copyOf(rows) : Collections.emptyList();
        if (message == null) message = "OK";
    }

    public int getRowCount() {
        return rows.size();
    }

    public int getColumnCount() {
        return columns.size();
    }

    public Object getValue(int rowIndex, int colIndex) {
        return rows.get(rowIndex).get(colIndex);
    }
}
