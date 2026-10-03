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
public record Schema(List<ColumnDef> columns) {

    public Schema {
        columns = List.copyOf(columns);
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
