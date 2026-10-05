package dev.koradb.catalog;

import java.util.Objects;

/**
 * Descriptive statistics for a single column used in query cost and cardinality estimation.
 *
 * @param columnName    the column name
 * @param distinctCount estimated number of distinct values (NDV)
 * @param nullCount     number of null values
 * @param minValue      minimum observed value (or null)
 * @param maxValue      maximum observed value (or null)
 */
public record ColumnStatistics(
        String columnName,
        long distinctCount,
        long nullCount,
        Object minValue,
        Object maxValue
) {
    public ColumnStatistics {
        Objects.requireNonNull(columnName);
        distinctCount = Math.max(0, distinctCount);
        nullCount = Math.max(0, nullCount);
    }

    public static ColumnStatistics defaultStats(String columnName) {
        return new ColumnStatistics(columnName, 10, 0, null, null);
    }
}
