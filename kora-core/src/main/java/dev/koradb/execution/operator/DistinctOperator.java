package dev.koradb.execution.operator;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.sql.ast.ColumnDef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Returns each distinct combination of output-column values once (NULLs compare equal here). */
public class DistinctOperator implements Operator {

    private final Operator child;
    private Set<List<Object>> seen;

    public DistinctOperator(Operator child) {
        this.child = child;
    }

    @Override
    public void open() {
        child.open();
        seen = new HashSet<>();
    }

    @Override
    public Row next() {
        Row r;
        while ((r = child.next()) != null) {
            List<Object> key = new ArrayList<>();
            for (ColumnDef c : child.getSchema().columns()) {
                key.add(JoinSupport.hashKey(r.get(c.name())));
            }
            if (seen.add(key)) return r;
        }
        return null;
    }

    @Override
    public void close() {
        seen = null;
        child.close();
    }

    @Override
    public Schema getSchema() {
        return child.getSchema();
    }
}
