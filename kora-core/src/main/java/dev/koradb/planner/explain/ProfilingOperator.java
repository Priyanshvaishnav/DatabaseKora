package dev.koradb.planner.explain;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.execution.operator.Operator;

import java.util.Objects;

/**
 * An instrumenting decorator for Volcano operators that records actual row counts
 * and wall-clock execution time during query execution (for {@code EXPLAIN ANALYZE}).
 */
public final class ProfilingOperator implements Operator {

    private final Operator delegate;
    private long openTimeNanos = 0;
    private long totalNextTimeNanos = 0;
    private long closeTimeNanos = 0;
    private long emittedRows = 0;

    public ProfilingOperator(Operator delegate) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate operator must not be null");
    }

    public Operator getDelegate() {
        return delegate;
    }

    public long getEmittedRows() {
        return emittedRows;
    }

    public double getTotalTimeMillis() {
        return (openTimeNanos + totalNextTimeNanos + closeTimeNanos) / 1_000_000.0;
    }

    @Override
    public void open() {
        long start = System.nanoTime();
        delegate.open();
        openTimeNanos = System.nanoTime() - start;
    }

    @Override
    public Row next() {
        long start = System.nanoTime();
        Row row = delegate.next();
        totalNextTimeNanos += (System.nanoTime() - start);
        if (row != null) {
            emittedRows++;
        }
        return row;
    }

    @Override
    public void close() {
        long start = System.nanoTime();
        delegate.close();
        closeTimeNanos = System.nanoTime() - start;
    }

    @Override
    public Schema getSchema() {
        return delegate.getSchema();
    }
}
