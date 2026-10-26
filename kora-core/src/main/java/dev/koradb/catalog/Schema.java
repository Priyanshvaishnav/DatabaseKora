package dev.koradb.catalog;

import dev.koradb.sql.ast.ColumnDef;

import java.util.List;

/**
 * The schema of a single table — an ordered list of column definitions.
 *
 * <p>Column order matters: it determines positional INSERT value mapping.
 *
 * @param columns ordered column definitions; immutable after creation
 */
public record Schema(List<ColumnDef> columns, int storedColumnCount) {

    /**
     * @param storedColumnCount number of columns in records written without a column count: the
     *                          table's column count before its first ALTER TABLE ADD COLUMN
     *                          (equal to {@code columns.size()} for a table never altered)
     */
    public Schema {
        columns = List.copyOf(columns);
        if (storedColumnCount <= 0 || storedColumnCount > columns.size()) storedColumnCount = columns.size();
    }

    public Schema(List<ColumnDef> columns) {
        this(columns, columns.size());
    }

    /** True after ALTER TABLE ADD COLUMN: new records carry their column count. */
    public boolean altered() {
        return storedColumnCount < columns.size();
    }

    /** This schema with {@code column} added at the end (ALTER TABLE ADD COLUMN). */
    public Schema withColumn(ColumnDef column) {
        List<ColumnDef> cols = new java.util.ArrayList<>(columns);
        cols.add(column);
        return new Schema(cols, storedColumnCount);
    }

    /**
     * Returns the {@link ColumnDef} for the given column name, or
     * {@code null} if not found.
     */
    public ColumnDef findColumn(String name) {
        return columns.stream()
                .filter(c -> c.name().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns true if a column with the given name exists in this schema.
     */
    public boolean hasColumn(String name) {
        return findColumn(name) != null;
    }

    /** Returns the 0-based index of the column, or -1 if not found. */
    public int indexOf(String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }
}
