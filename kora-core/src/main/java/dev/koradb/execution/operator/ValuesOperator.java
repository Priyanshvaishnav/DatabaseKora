package dev.koradb.execution.operator;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;

import java.util.LinkedHashMap;
import java.util.List;

/** Produces one empty row (SELECT without FROM). */
public class ValuesOperator implements Operator {

    private boolean done;

    @Override
    public void open() {
        done = false;
    }

    @Override
    public Row next() {
        if (done) return null;
        done = true;
        return new Row(new LinkedHashMap<>());
    }

    @Override
    public void close() {
    }

    @Override
    public Schema getSchema() {
        return new Schema(List.of());
    }
}
