package dev.koradb.protocol;

import java.util.Collections;
import java.util.List;

/**
 * Server response message containing query or statement execution results.
 *
 * @param isQuery      true if SELECT query with result set, false if DDL/DML statement
 * @param affectedRows count of modified rows for DML statements
 * @param message      status message (e.g., "OK", "CREATE TABLE", etc.)
 * @param columns      ordered column names
 * @param rows         ordered list of rows, each containing column values
 */
public record QueryResultMessage(
        boolean isQuery,
        int affectedRows,
        String message,
        List<String> columns,
        List<List<Object>> rows
) implements Message {

    public QueryResultMessage {
        if (message == null) message = "OK";
        columns = columns != null ? List.copyOf(columns) : Collections.emptyList();
        rows = rows != null ? List.copyOf(rows) : Collections.emptyList();
    }

    public static QueryResultMessage rows(List<String> columns, List<List<Object>> rows) {
        return new QueryResultMessage(true, 0, "OK", columns, rows);
    }

    public static QueryResultMessage dml(int affectedRows) {
        return new QueryResultMessage(false, affectedRows, affectedRows + " row(s) affected", List.of(), List.of());
    }

    public static QueryResultMessage ddl() {
        return new QueryResultMessage(false, 0, "OK", List.of(), List.of());
    }

    public static QueryResultMessage status(String message) {
        return new QueryResultMessage(false, 0, message, List.of(), List.of());
    }

    @Override
    public MessageType getType() {
        return MessageType.QUERY_RESULT;
    }
}
