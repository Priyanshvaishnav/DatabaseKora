package dev.koradb.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A single row in a table — a mapping from column name to value.
 *
 * <p>Values are Java objects typed according to the column's {@link dev.koradb.sql.ast.DataType}:
 * <ul>
 *   <li>INT       → {@link Integer}</li>
 *   <li>BIGINT    → {@link Long}</li>
 *   <li>BOOLEAN   → {@link Boolean}</li>
 *   <li>VARCHAR   → {@link String}</li>
 *   <li>DOUBLE    → {@link Double}</li>
 *   <li>TIMESTAMP → {@link java.time.LocalDateTime}</li>
 * </ul>
 *
 * <p>Design note: using {@link LinkedHashMap} preserves column insertion order,
 * which matters for {@code SELECT *} output ordering.
 *
 * <p>Rows are mutable inside the catalog (UPDATE modifies them in-place).
 * Snapshots for MVCC will be added in Phase 10.
 */
public final class Row {

    private final Map<String, Object> values;
    private long xmin;
    private long xmax;

    public Row(Map<String, Object> values) {
        this(values, 0L, 0L);
    }

    public Row(Map<String, Object> values, long xmin, long xmax) {
        this.values = new LinkedHashMap<>(values);
        this.xmin = xmin;
        this.xmax = xmax;
    }

    public long getXmin() {
        return xmin;
    }

    public void setXmin(long xmin) {
        this.xmin = xmin;
    }

    public long getXmax() {
        return xmax;
    }

    public void setXmax(long xmax) {
        this.xmax = xmax;
    }

    public Row withXmax(long xmax) {
        return new Row(this.values, this.xmin, xmax);
    }

    public Row withXmin(long xmin) {
        return new Row(this.values, xmin, this.xmax);
    }

    /** Returns the value for the given column name, or {@code null} if absent. */
    public Object get(String columnName) {
        if (columnName == null) return null;
        // An exact key that is present wins, even when its value is NULL: a NULL "orders.id" from a
        // LEFT JOIN must not fall back to another table's "id".
        if (values.containsKey(columnName)) return values.get(columnName);

        // Case-insensitive direct match
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(columnName)) {
                return entry.getValue();
            }
        }

        // Table-prefixed or short name match (case-insensitive)
        String targetShort = columnName.contains(".") ? columnName.substring(columnName.indexOf('.') + 1) : columnName;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            String keyShort = key.contains(".") ? key.substring(key.indexOf('.') + 1) : key;
            if (keyShort.equalsIgnoreCase(targetShort) || key.equalsIgnoreCase(columnName)) {
                return entry.getValue();
            }
        }

        return null;
    }

    /** True when the row has a value (possibly NULL) under exactly this name, ignoring case. */
    public boolean hasKey(String columnName) {
        if (values.containsKey(columnName)) return true;
        for (String k : values.keySet()) {
            if (k.equalsIgnoreCase(columnName)) return true;
        }
        return false;
    }

    /** Sets (or overwrites) a value for the given column name. */
    public void set(String columnName, Object value) {
        values.put(columnName, value);
    }

    /** Returns an unmodifiable view of the row's values, preserving order. */
    public Map<String, Object> values() {
        return Collections.unmodifiableMap(values);
    }

    /** Creates a shallow copy of this row (used during UPDATE to avoid aliasing). */
    public Row copy() {
        return new Row(new LinkedHashMap<>(values), xmin, xmax);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Row other)) return false;
        return Objects.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values);
    }

    @Override
    public String toString() {
        return "Row" + values.toString();
    }
}
