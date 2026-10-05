package dev.koradb.catalog;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Summary statistics for a database table, used by the Cost-Based Optimizer (CBO)
 * to estimate cardinalities and I/O / CPU operational costs.
 *
 * @param tableName   name of the table
 * @param rowCount    total number of rows (cardinality)
 * @param pageCount   total number of 4KB pages occupied on disk
 * @param columnStats per-column statistics (NDV, min, max, nulls)
 */
public record TableStatistics(
        String tableName,
        long rowCount,
        int pageCount,
        Map<String, ColumnStatistics> columnStats
) {
    public TableStatistics {
        Objects.requireNonNull(tableName);
        rowCount = Math.max(0, rowCount);
        pageCount = Math.max(1, pageCount);
        columnStats = columnStats != null ? Map.copyOf(columnStats) : Collections.emptyMap();
    }

    /**
     * Returns statistics for the requested column, or default placeholder statistics if not present.
     */
    public ColumnStatistics getColumnStats(String column) {
        if (column == null) {
            return ColumnStatistics.defaultStats("unknown");
        }
        String lookup = column.contains(".") ? column.substring(column.indexOf('.') + 1) : column;
        for (Map.Entry<String, ColumnStatistics> entry : columnStats.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(lookup) || entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        return ColumnStatistics.defaultStats(column);
    }

    /**
     * Estimated number of distinct values (NDV) for a given column.
     */
    public long distinctValues(String column) {
        return Math.max(1, getColumnStats(column).distinctCount());
    }

    public static TableStatistics empty(String tableName) {
        return new TableStatistics(tableName, 0, 1, Collections.emptyMap());
    }
}
