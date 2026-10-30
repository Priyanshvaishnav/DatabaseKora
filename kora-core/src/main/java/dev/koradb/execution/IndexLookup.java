package dev.koradb.execution;

import dev.koradb.catalog.Catalog;
import dev.koradb.index.Index;
import dev.koradb.index.IndexKey;
import dev.koradb.sql.ast.BinaryExpr;
import dev.koradb.sql.ast.ColumnRefExpr;
import dev.koradb.sql.ast.DataType;
import dev.koradb.sql.ast.Expression;
import dev.koradb.sql.ast.InExpr;
import dev.koradb.sql.ast.LiteralExpr;
import dev.koradb.sql.ast.WhereClause;
import dev.koradb.storage.RecordId;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Finds the rows a WHERE clause can match through an index.
 *
 * <p>Index entries are hints: one key can have entries for dead versions of a row, so a caller
 * must still read each row under its snapshot and evaluate the whole WHERE clause on it.
 */
public final class IndexLookup {

    private IndexLookup() {}

    private static final Comparator<RecordId> PHYSICAL_ORDER =
            Comparator.comparingInt(RecordId::pageId).thenComparingInt(RecordId::slotIndex);

    /**
     * Candidate RIDs for {@code where}, in physical order (the order a table scan visits them), or
     * empty when no top-level condition is {@code col = literal} or {@code col IN (literals)} on an
     * indexed column and the table has to be scanned. A unique index is preferred over others.
     */
    public static Optional<List<RecordId>> candidates(String tableName, WhereClause where, Catalog catalog) {
        Index chosen = null;
        List<IndexKey> chosenKeys = null;
        for (Expression predicate : where.predicates()) {
            List<Object> literals = new ArrayList<>();
            String column = equalityColumn(predicate, literals);
            if (column == null) continue;
            int dot = column.indexOf('.');
            if (dot > 0) {
                if (!column.substring(0, dot).equalsIgnoreCase(tableName)) continue;
                column = column.substring(dot + 1);
            }
            Optional<Index> index = catalog.getIndexForColumn(tableName, column);
            if (index.isEmpty()) continue;
            List<IndexKey> keys = keysFor(literals, index.get().getKeyType());
            if (keys == null) continue;
            if (chosen == null || (index.get().isUnique() && !chosen.isUnique())) {
                chosen = index.get();
                chosenKeys = keys;
            }
        }
        if (chosen == null) return Optional.empty();

        TreeSet<RecordId> rids = new TreeSet<>(PHYSICAL_ORDER);   // an index may hold the same RID twice
        for (IndexKey key : chosenKeys) {
            rids.addAll(chosen.searchAll(key));
        }
        return Optional.of(new ArrayList<>(rids));
    }

    /**
     * The key that finds exactly the rows equal to {@code literal}, or null if the literal has no
     * exact representation in {@code type} and the column must be compared by a scan. A plain
     * {@code new IndexKey(7.5, INT)} truncates to 7, and would find the wrong rows.
     */
    public static IndexKey keyFor(Object literal, DataType type) {
        return switch (type) {
            case INT -> literal instanceof Number n && isIntegral(n) && n.doubleValue() >= Integer.MIN_VALUE
                    && n.doubleValue() <= Integer.MAX_VALUE ? new IndexKey(n, type) : null;
            case BIGINT -> literal instanceof Number n && isIntegral(n) && n.doubleValue() >= Long.MIN_VALUE
                    && n.doubleValue() < 0x1p63 ? new IndexKey(n, type) : null;
            case DOUBLE -> literal instanceof Number n ? new IndexKey(n, type) : null;
            case BOOLEAN -> literal instanceof Boolean ? new IndexKey(literal, type) : null;
            case VARCHAR -> literal instanceof String ? new IndexKey(literal, type) : null;
            case TIMESTAMP -> timestampKey(literal);
        };
    }

    private static boolean isIntegral(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            return !Double.isInfinite(d) && d == Math.rint(d);
        }
        return true;
    }

    private static IndexKey timestampKey(Object literal) {
        if (literal instanceof LocalDateTime t) return new IndexKey(t, DataType.TIMESTAMP);
        if (!(literal instanceof String s)) return null;
        try {
            return new IndexKey(TimestampParser.parse(s), DataType.TIMESTAMP);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The column of {@code col = literal} / {@code col IN (literals)}, with its literals; else null. */
    private static String equalityColumn(Expression predicate, List<Object> literals) {
        if (predicate instanceof BinaryExpr b && b.operator() == dev.koradb.sql.ast.Operator.EQ) {
            ColumnRefExpr column = b.left() instanceof ColumnRefExpr c ? c : b.right() instanceof ColumnRefExpr c ? c : null;
            LiteralExpr literal = b.right() instanceof LiteralExpr l ? l : b.left() instanceof LiteralExpr l ? l : null;
            if (column == null || literal == null) return null;
            literals.add(literal.value());
            return column.columnName();
        }
        if (predicate instanceof InExpr in && !in.negated() && in.list() != null && in.operand() instanceof ColumnRefExpr c) {
            for (Expression e : in.list()) {
                if (!(e instanceof LiteralExpr l)) return null;
                literals.add(l.value());
            }
            return c.columnName();
        }
        return null;
    }

    /** Keys for every literal; null if any cannot be looked up. NULL matches no row, so it needs no key. */
    private static List<IndexKey> keysFor(List<Object> literals, DataType type) {
        List<IndexKey> keys = new ArrayList<>(literals.size());
        for (Object literal : literals) {
            if (literal == null) continue;
            IndexKey key = keyFor(literal, type);
            if (key == null) return null;
            keys.add(key);
        }
        return keys;
    }
}
