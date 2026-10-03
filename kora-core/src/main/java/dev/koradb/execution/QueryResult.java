package dev.koradb.execution;

import dev.koradb.catalog.Row;

import java.util.List;
import java.util.Map;

/**
 * The result of executing any SQL statement.
 *
 * <p>For DML statements (INSERT, UPDATE, DELETE):
 * <ul>
 *   <li>{@link #rows()} is empty</li>
 *   <li>{@link #affectedRows()} is the count of rows inserted/updated/deleted</li>
 * </ul>
 *
 * <p>For SELECT:
 * <ul>
 *   <li>{@link #rows()} contains the result set</li>
 *   <li>{@link #affectedRows()} is 0</li>
 * </ul>
 *
 * <p>For DDL (CREATE/DROP):
 * <ul>
 *   <li>{@link #rows()} is empty</li>
 *   <li>{@link #affectedRows()} is 0</li>
 * </ul>
 *
 * @param rows         result rows for SELECT; empty otherwise
 * @param affectedRows number of rows affected by DML; 0 for DDL and SELECT
 * @param columns      ordered column names for the result (empty for DDL/DML)
 */
public record QueryResult(
        List<Row>    rows,
        int          affectedRows,
        List<String> columns
) {

    /** Convenience factory for DDL statements (no rows, no affected count). */
    public static QueryResult ddl() {
        return new QueryResult(List.of(), 0, List.of());
    }

    /** Convenience factory for DML statements (no result rows, just affected count). */
    public static QueryResult dml(int affectedRows) {
        return new QueryResult(List.of(), affectedRows, List.of());
    }

    /** Convenience factory for SELECT results. */
    public static QueryResult select(List<Row> rows, List<String> columns) {
        return new QueryResult(List.copyOf(rows), 0, List.copyOf(columns));
    }

    /** Returns true if this result has no rows and no affected count. */
    public boolean isEmpty() {
        return rows.isEmpty() && affectedRows == 0;
    }

    /**
     * Prints the result in a simple tabular format — useful for the CLI and tests.
     */
    public String toDisplayString() {
        if (!columns.isEmpty() && !rows.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.join(" | ", columns)).append('\n');
            sb.append("-".repeat(columns.stream().mapToInt(String::length).sum()
                    + (columns.size() - 1) * 3)).append('\n');
            for (Row row : rows) {
                Map<String, Object> vals = row.values();
                sb.append(columns.stream()
                        .map(c -> String.valueOf(vals.get(c)))
                        .reduce((a, b) -> a + " | " + b)
                        .orElse(""))
                  .append('\n');
            }
            return sb.toString();
        }
        if (affectedRows > 0) {
            return affectedRows + " row(s) affected\n";
        }
        return "OK\n";
    }
}
