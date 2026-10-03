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

    public Row(Map<String, Object> values) {
        // LinkedHashMap to preserve column order
        this.values = new LinkedHashMap<>(values);
    }

    /** Returns the value for the given column name, or {@code null} if absent. */
    public Object get(String columnName) {
        return values.get(columnName);
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
        return new Row(new LinkedHashMap<>(values));
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
