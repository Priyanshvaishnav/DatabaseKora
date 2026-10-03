package dev.koradb.execution;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.exception.ExecutionException;
import dev.koradb.exception.SchemaException;
import dev.koradb.sql.ast.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Evaluates {@link Expression} nodes against a {@link Row}.
 *
 * <h2>Supported expressions in Milestone 1</h2>
 * <ul>
 *   <li>{@link BinaryExpr} — comparison of a column value against a literal</li>
 *   <li>{@link LiteralExpr} — a constant value</li>
 *   <li>{@link ColumnRefExpr} — reads a value from the current row</li>
 * </ul>
 *
 * <h2>Type coercion rules</h2>
 * <ul>
 *   <li>Numeric types: INT and DOUBLE can be compared (int is promoted to double).</li>
 *   <li>BIGINT and INT can be compared (int is promoted to long).</li>
 *   <li>TIMESTAMP strings are parsed when the schema column type is TIMESTAMP.</li>
 *   <li>All other type mismatches throw {@link ExecutionException}.</li>
 * </ul>
 */
public final class ExpressionEvaluator {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Schema schema;

    /**
     * @param schema the schema of the table being queried,
     *               used to resolve column types for type checking
     */
    public ExpressionEvaluator(Schema schema) {
        this.schema = schema;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Evaluates a {@link BinaryExpr} predicate against a row.
     *
     * @param expr the predicate to evaluate
     * @param row  the row to evaluate it against
     * @return true if the predicate is satisfied by this row
     * @throws ExecutionException on type mismatch or unknown column
     */
    public boolean evaluate(BinaryExpr expr, Row row) {
        Object leftVal  = resolveLeft(expr.left(), row);
        Object rightVal = resolveRight(expr.right(), expr.left(), row);

        return compare(leftVal, rightVal, expr.operator());
    }

    /**
     * Resolves a literal value, applying TIMESTAMP parsing if the target
     * column's type is TIMESTAMP.
     *
     * @param literal    the literal to resolve
     * @param columnName the name of the target column (for type lookup)
     */
    public Object resolveLiteral(LiteralExpr literal, String columnName) {
        Object raw = literal.value();
        if (raw instanceof String s) {
            ColumnDef col = schema.findColumn(columnName);
            if (col != null && col.dataType() == DataType.TIMESTAMP) {
                return parseTimestamp(s);
            }
        }
        return raw;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Object resolveLeft(Expression left, Row row) {
        if (left instanceof ColumnRefExpr ref) {
            String colName = ref.columnName();
            if (!schema.hasColumn(colName)) {
                throw new SchemaException(
                        "Column '%s' does not exist".formatted(colName));
            }
            return row.get(colName);
        }
        if (left instanceof LiteralExpr lit) {
            return lit.value();
        }
        throw new ExecutionException("Unsupported left-hand expression: " + left.getClass().getSimpleName());
    }

    private Object resolveRight(Expression right, Expression left, Row row) {
        if (right instanceof LiteralExpr lit) {
            // If left is a column reference, check if we need TIMESTAMP parsing
            if (left instanceof ColumnRefExpr ref) {
                return resolveLiteral(lit, ref.columnName());
            }
            return lit.value();
        }
        if (right instanceof ColumnRefExpr ref) {
            return row.get(ref.columnName());
        }
        throw new ExecutionException("Unsupported right-hand expression: " + right.getClass().getSimpleName());
    }

    /**
     * Compares two values using the given operator.
     *
     * <p>Null handling: any comparison involving null returns false (SQL NULL semantics).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean compare(Object left, Object right, Operator op) {
        // SQL NULL semantics: any comparison with null → false
        if (left == null || right == null) return false;

        // Numeric promotion
        Object[] promoted = promoteNumerics(left, right);
        left  = promoted[0];
        right = promoted[1];

        if (!(left instanceof Comparable) || !(right instanceof Comparable)) {
            throw new ExecutionException(
                    "Cannot compare values of types %s and %s"
                            .formatted(left.getClass().getSimpleName(),
                                       right.getClass().getSimpleName()));
        }

        // Ensure both sides are the same type after promotion
        if (!left.getClass().equals(right.getClass())) {
            throw new ExecutionException(
                    "Type mismatch: cannot compare %s (%s) with %s (%s)"
                            .formatted(left, left.getClass().getSimpleName(),
                                       right, right.getClass().getSimpleName()));
        }

        int cmp = ((Comparable) left).compareTo(right);

        return switch (op) {
            case EQ  -> cmp == 0;
            case NEQ -> cmp != 0;
            case LT  -> cmp <  0;
            case GT  -> cmp >  0;
            case LTE -> cmp <= 0;
            case GTE -> cmp >= 0;
        };
    }

    /**
     * Promotes numeric types so INT vs DOUBLE and INT vs LONG comparisons work.
     *
     * <ul>
     *   <li>INT + DOUBLE  → both become DOUBLE</li>
     *   <li>INT + LONG    → both become LONG</li>
     *   <li>LONG + DOUBLE → both become DOUBLE</li>
     *   <li>Anything else → unchanged (type mismatch will be caught above)</li>
     * </ul>
     */
    private Object[] promoteNumerics(Object left, Object right) {
        if (left instanceof Integer l && right instanceof Double r) {
            return new Object[]{l.doubleValue(), r};
        }
        if (left instanceof Double l && right instanceof Integer r) {
            return new Object[]{l, r.doubleValue()};
        }
        if (left instanceof Integer l && right instanceof Long r) {
            return new Object[]{l.longValue(), r};
        }
        if (left instanceof Long l && right instanceof Integer r) {
            return new Object[]{l, r.longValue()};
        }
        if (left instanceof Long l && right instanceof Double r) {
            return new Object[]{l.doubleValue(), r};
        }
        if (left instanceof Double l && right instanceof Long r) {
            return new Object[]{l, r.doubleValue()};
        }
        return new Object[]{left, right};
    }

    private LocalDateTime parseTimestamp(String s) {
        try {
            return LocalDateTime.parse(s, TIMESTAMP_FORMAT);
        } catch (DateTimeParseException e) {
            throw new ExecutionException(
                    "Cannot parse '%s' as TIMESTAMP. Expected format: yyyy-MM-dd HH:mm:ss".formatted(s));
        }
    }
}
