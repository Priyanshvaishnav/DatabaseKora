package dev.koradb.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a single database table — its schema and all stored rows.
 *
 * <p>In Milestone 1, rows are stored as an in-memory {@link ArrayList}.
 * This will be replaced by a disk-backed page manager in Milestone 2.
 *
 * <p>Table is mutable (rows can be inserted/updated/deleted). Thread-safety
 * is not guaranteed in M1; concurrent access will be addressed in Phase 7
 * (transactions) and Phase 10 (MVCC).
 *
 * @param name   the table name (case-preserved)
 * @param schema the column definitions
 */
public class Table {

    private final String       name;
    private final Schema       schema;
    private final List<Row>    rows;

    public Table(String name, Schema schema) {
        this.name   = name;
        this.schema = schema;
        this.rows   = new ArrayList<>();
    }

    public String getName() { return name; }
    public Schema getSchema() { return schema; }

    /** Returns an unmodifiable snapshot of current rows. */
    public List<Row> getRows() {
        return Collections.unmodifiableList(rows);
    }

    /** Appends a new row. Called by the INSERT executor. */
    public void insertRow(Row row) {
        rows.add(row);
    }

    /**
     * Removes a row by its list index.
     * Called by DELETE executor after identifying rows to remove.
     */
    public void deleteRow(int index) {
        rows.remove(index);
    }

    /**
     * Returns the internal mutable row list.
     *
     * <p>Used only by the UPDATE and DELETE executors for direct in-place modification.
     * In later milestones this will be replaced by page-level write operations.
     */
    public List<Row> mutableRows() {
        return rows;
    }

    /** Returns the number of rows currently stored in the table. */
    public int rowCount() {
        return rows.size();
    }

    @Override
    public String toString() {
        return "Table{name='%s', columns=%d, rows=%d}"
                .formatted(name, schema.columns().size(), rows.size());
    }
}
