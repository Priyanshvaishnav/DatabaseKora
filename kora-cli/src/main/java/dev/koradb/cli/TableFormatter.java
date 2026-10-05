package dev.koradb.cli;

import dev.koradb.client.ClientQueryResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Pretty-prints query results as aligned ASCII tables.
 */
public final class TableFormatter {

    private TableFormatter() {}

    /**
     * Formats a {@link ClientQueryResult} into a readable tabular ASCII representation.
     */
    public static String format(ClientQueryResult result) {
        if (!result.isQuery()) {
            if (result.affectedRows() > 0) {
                return String.format("Query OK, %d row(s) affected (%.2f ms)%n",
                        result.affectedRows(), result.executionTimeMs() * 1.0);
            }
            return String.format("Query OK (%.2f ms)%n", result.executionTimeMs() * 1.0);
        }

        List<String> cols = result.columns();
        List<List<Object>> rows = result.rows();

        if (cols.isEmpty()) {
            return String.format("Empty set (%.2f ms)%n", result.executionTimeMs() * 1.0);
        }

        // Determine column widths
        int colCount = cols.size();
        int[] widths = new int[colCount];

        for (int i = 0; i < colCount; i++) {
            widths[i] = Math.max(widths[i], cols.get(i).length());
        }

        for (List<Object> row : rows) {
            for (int i = 0; i < colCount; i++) {
                Object val = i < row.size() ? row.get(i) : null;
                String str = val != null ? String.valueOf(val) : "NULL";
                widths[i] = Math.max(widths[i], str.length());
            }
        }

        StringBuilder sb = new StringBuilder();

        // Top border
        String border = buildBorder(widths);
        sb.append(border).append('\n');

        // Header row
        sb.append('|');
        for (int i = 0; i < colCount; i++) {
            sb.append(' ').append(padRight(cols.get(i), widths[i])).append(" |");
        }
        sb.append('\n');

        // Middle border
        sb.append(border).append('\n');

        // Data rows
        for (List<Object> row : rows) {
            sb.append('|');
            for (int i = 0; i < colCount; i++) {
                Object val = i < row.size() ? row.get(i) : null;
                boolean isNumeric = val instanceof Number;
                String str = val != null ? String.valueOf(val) : "NULL";

                sb.append(' ');
                if (isNumeric) {
                    sb.append(padLeft(str, widths[i]));
                } else {
                    sb.append(padRight(str, widths[i]));
                }
                sb.append(" |");
            }
            sb.append('\n');
        }

        // Bottom border
        sb.append(border).append('\n');

        // Summary footer
        sb.append(String.format("%d row%s in set (%.2f ms)%n",
                rows.size(), rows.size() == 1 ? "" : "s", result.executionTimeMs() * 1.0));

        return sb.toString();
    }

    private static String buildBorder(int[] widths) {
        StringBuilder sb = new StringBuilder("+");
        for (int w : widths) {
            sb.append("-".repeat(w + 2)).append('+');
        }
        return sb.toString();
    }

    private static String padRight(String s, int n) {
        if (s.length() >= n) return s;
        return s + " ".repeat(n - s.length());
    }

    private static String padLeft(String s, int n) {
        if (s.length() >= n) return s;
        return " ".repeat(n - s.length()) + s;
    }
}
