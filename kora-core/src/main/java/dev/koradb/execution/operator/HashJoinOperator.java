package dev.koradb.execution.operator;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;

import java.util.*;

/**
 * Equi-join {@code outerCol = innerCol}: builds a hash table on the inner side, probes with the outer.
 * NULL keys never match. With {@code leftOuter}, unmatched outer rows get NULLs for the inner side.
 */
public class HashJoinOperator implements Operator {

    private final Operator outer;
    private final Operator inner;
    private final String outerCol;
    private final String innerCol;
    private final boolean leftOuter;
    private final List<String> innerQualifiers;
    private final Schema schema;

    private Map<Object, List<Row>> hashTable;
    private Row currentOuter;
    private Iterator<Row> currentMatches;

    public HashJoinOperator(Operator outer, Operator inner, String outerCol, String innerCol) {
        this(outer, inner, outerCol, innerCol, false, List.of());
    }

    public HashJoinOperator(Operator outer, Operator inner, String outerCol, String innerCol,
                            boolean leftOuter, List<String> innerQualifiers) {
        this.outer = outer;
        this.inner = inner;
        this.outerCol = outerCol;
        this.innerCol = innerCol;
        this.leftOuter = leftOuter;
        this.innerQualifiers = List.copyOf(innerQualifiers);
        this.schema = JoinSupport.mergeSchemas(outer.getSchema(), inner.getSchema());
    }

    @Override
    public void open() {
        inner.open();
        hashTable = new HashMap<>();
        Row innerRow;
        while ((innerRow = inner.next()) != null) {
            Object key = JoinSupport.hashKey(innerRow.get(innerCol));
            if (key != null) {
                hashTable.computeIfAbsent(key, k -> new ArrayList<>()).add(innerRow);
            }
        }
        inner.close();
        outer.open();
        currentMatches = Collections.emptyIterator();
    }

    @Override
    public Row next() {
        while (true) {
            if (currentMatches != null && currentMatches.hasNext()) {
                return JoinSupport.mergeRows(currentOuter, currentMatches.next());
            }
            currentOuter = outer.next();
            if (currentOuter == null) {
                return null;
            }
            Object key = JoinSupport.hashKey(currentOuter.get(outerCol));
            List<Row> matches = key != null ? hashTable.get(key) : null;
            if (matches != null) {
                currentMatches = matches.iterator();
            } else {
                currentMatches = Collections.emptyIterator();
                if (leftOuter) {
                    return JoinSupport.mergeRows(currentOuter, JoinSupport.nullRow(inner.getSchema(), innerQualifiers));
                }
            }
        }
    }

    @Override
    public void close() {
        hashTable = null;
        currentOuter = null;
        currentMatches = null;
        outer.close();
    }

    @Override
    public Schema getSchema() {
        return schema;
    }
}
