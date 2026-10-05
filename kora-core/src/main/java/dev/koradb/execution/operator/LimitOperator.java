package dev.koradb.execution.operator;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;

/**
 * Limit and Offset operator.
 *
 * <p>Skips the first {@code offset} tuples emitted by the child operator,
 * then passes through up to {@code limit} tuples. Stops pulling from the child
 * operator as soon as {@code limit} tuples have been returned (early termination).
 */
public class LimitOperator implements Operator {

    private final Operator child;
    private final int limit;
    private final int offset;
    private int returnedCount = 0;

    public LimitOperator(Operator child, int limit, int offset) {
        this.child = child;
        this.limit = limit;
        this.offset = Math.max(0, offset);
    }

    @Override
    public void open() {
        child.open();
        returnedCount = 0;
        for (int i = 0; i < offset; i++) {
            Row r = child.next();
            if (r == null) break;
        }
    }

    @Override
    public Row next() {
        if (limit >= 0 && returnedCount >= limit) {
            return null;
        }
        Row row = child.next();
        if (row != null) {
            returnedCount++;
        }
        return row;
    }

    @Override
    public void close() {
        child.close();
    }

    @Override
    public Schema getSchema() {
        return child.getSchema();
    }
}
