package dev.koradb.catalog;

import dev.koradb.mvcc.MvccContext;
import dev.koradb.mvcc.ReadView;
import dev.koradb.mvcc.WriteConflictException;
import dev.koradb.transaction.Transaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Represents a single database table — its schema and stored row versions.
 *
 * <p>Supports Multi-Version Concurrency Control (MVCC) with non-blocking reads,
 * point-in-time snapshot isolation, and first-committer-wins conflict detection.
 */
public class Table {

    private final String    name;
    private final Schema    schema;
    private final List<Row> rows;

    public Table(String name, Schema schema) {
        this.name   = name;
        this.schema = schema;
        this.rows   = new ArrayList<>();
    }

    public String getName() { return name; }
    public Schema getSchema() { return schema; }

    /**
     * Returns an unmodifiable snapshot of current rows matching the active ReadView.
     */
    public List<Row> getRows() {
        return getRows(MvccContext.getReadView());
    }

    /**
     * Returns an unmodifiable list of rows visible to the given ReadView snapshot.
     */
    public List<Row> getRows(ReadView readView) {
        synchronized (rows) {
            List<Row> visible = new ArrayList<>();
            for (Row r : rows) {
                if (readView != null) {
                    if (readView.isVisible(r.getXmin(), r.getXmax())) {
                        visible.add(r);
                    }
                } else {
                    if (r.getXmax() == 0L) {
                        visible.add(r);
                    }
                }
            }
            return Collections.unmodifiableList(visible);
        }
    }

    /**
     * Returns all row versions in the table regardless of visibility (for vacuuming and testing).
     */
    public List<Row> getAllVersions() {
        synchronized (rows) {
            return Collections.unmodifiableList(new ArrayList<>(rows));
        }
    }

    /** Appends a new row. Called by the INSERT executor. */
    public void insertRow(Row row) {
        insertRow(row, null);
    }

    /**
     * Appends a new row stamped with the creating transaction's ID.
     */
    public void insertRow(Row row, Transaction txn) {
        if (txn != null) {
            row.setXmin(txn.getId().value());
            row.setXmax(0L);
        }
        synchronized (rows) {
            rows.add(row);
        }
    }

    /**
     * Updates an existing row by setting xmax on the old row and appending the new row version.
     */
    public void updateRow(Row oldRow, Row newRow, Transaction txn) {
        long txnId = txn != null ? txn.getId().value() : 0L;
        synchronized (rows) {
            if (oldRow.getXmax() != 0L && txn != null && oldRow.getXmax() != txnId) {
                throw new WriteConflictException(oldRow.getXmax(), txnId);
            }
            oldRow.setXmax(txnId);
            if (txn != null) {
                newRow.setXmin(txnId);
                newRow.setXmax(0L);
            }
            rows.add(newRow);
        }
    }

    /**
     * Marks a row as deleted by setting its xmax.
     */
    public void deleteRow(Row row, Transaction txn) {
        long txnId = txn != null ? txn.getId().value() : 0L;
        synchronized (rows) {
            if (row.getXmax() != 0L && txn != null && row.getXmax() != txnId) {
                throw new WriteConflictException(row.getXmax(), txnId);
            }
            row.setXmax(txnId);
        }
    }

    /**
     * Removes a row by its list index (legacy / non-MVCC delete).
     */
    public void deleteRow(int index) {
        synchronized (rows) {
            rows.remove(index);
        }
    }

    /**
     * Returns the internal mutable row list.
     */
    public List<Row> mutableRows() {
        return rows;
    }

    /** Returns the number of visible rows currently stored in the table. */
    public int rowCount() {
        return getRows().size();
    }

    /**
     * Reclaims space by removing dead row versions whose xmax is older than lowWatermark.
     */
    public int vacuum(long lowWatermark, Predicate<Long> isCommitted) {
        synchronized (rows) {
            int initial = rows.size();
            rows.removeIf(r -> r.getXmax() != 0L && r.getXmax() < lowWatermark && isCommitted.test(r.getXmax()));
            return initial - rows.size();
        }
    }

    @Override
    public String toString() {
        return "Table{name='%s', columns=%d, rows=%d}"
                .formatted(name, schema.columns().size(), rowCount());
    }
}
