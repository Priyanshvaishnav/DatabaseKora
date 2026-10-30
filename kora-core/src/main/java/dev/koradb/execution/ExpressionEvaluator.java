package dev.koradb.execution;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.exception.ExecutionException;
import dev.koradb.exception.SchemaException;
import dev.koradb.sql.ast.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Evaluates expressions against a row with SQL semantics.
 *
 * <p>Boolean results use three-valued logic: {@code null} means UNKNOWN. Any comparison or
 * arithmetic with NULL is UNKNOWN; {@code NOT UNKNOWN} is UNKNOWN; {@code FALSE AND UNKNOWN} is
 * FALSE and {@code TRUE OR UNKNOWN} is TRUE. A WHERE / ON / HAVING condition keeps a row only when
 * it is TRUE ({@link #evaluate}).
 *
 * <p>Subqueries must be resolved to values before evaluation (see {@code SubqueryResolver}).
 */
public final class ExpressionEvaluator {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Map<String, Pattern> LIKE_CACHE = new ConcurrentHashMap<>();

    private final Schema schema;

    public ExpressionEvaluator(Schema schema) {
        this.schema = schema;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /** True only when the condition is TRUE for {@code row} (FALSE and UNKNOWN both reject). */
    public boolean evaluate(Expression condition, Row row) {
        Object v = eval(condition, row);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        throw new ExecutionException("Condition is not boolean: " + condition + " evaluated to " + v);
    }

    /** Value of {@code expr} for {@code row}: a column value, constant, Boolean, number or null. */
    public Object eval(Expression expr, Row row) {
        return switch (expr) {
            case LiteralExpr lit -> lit.value();
            case ColumnRefExpr ref -> resolveColumnValue(ref.columnName(), row);
            case AggregateExpr agg -> aggregateValue(agg, row);
            case UnaryExpr u -> evalUnary(u, row);
            case BinaryExpr b -> evalBinary(b, row);
            case InExpr in -> evalIn(in, row);
            case BetweenExpr bt -> evalBetween(bt, row);
            case SubqueryExpr sq -> throw new ExecutionException("Subquery was not resolved before execution");
        };
    }

    public Object resolveLiteral(LiteralExpr literal, String columnName) {
        Object raw = literal.value();
        if (raw instanceof String s && schema != null) {
            var col = schema.findColumn(columnName);
            if (col != null && col.dataType() == DataType.TIMESTAMP) {
                return parseTimestamp(s);
            }
        }
        return raw;
    }

    // =========================================================================
    // Operators
    // =========================================================================

    private Object evalUnary(UnaryExpr u, Row row) {
        Object v = eval(u.operand(), row);
        return switch (u.kind()) {
            case IS_NULL -> v == null;
            case IS_NOT_NULL -> v != null;
            case NOT -> v == null ? null : !asBoolean(v, u);
            case NEGATE -> {
                if (v == null) yield null;
                if (v instanceof Integer i) {
                    // (not a ?: expression: that would promote every result to long)
                    if (i == Integer.MIN_VALUE) yield -(long) i;
                    yield -i;
                }
                if (v instanceof Long l) yield Math.negateExact(l);
                if (v instanceof Double d) yield -d;
                throw new ExecutionException("Cannot negate " + typeName(v) + " value " + v);
            }
        };
    }

    private Object evalBinary(BinaryExpr b, Row row) {
        Operator op = b.operator();
        if (op == Operator.AND) {
            Object l = eval(b.left(), row);
            if (Boolean.FALSE.equals(l)) return false;           // short-circuit
            Object r = eval(b.right(), row);
            if (Boolean.FALSE.equals(r)) return false;
            if (l == null || r == null) return null;
            return asBoolean(l, b) && asBoolean(r, b);
        }
        if (op == Operator.OR) {
            Object l = eval(b.left(), row);
            if (Boolean.TRUE.equals(l)) return true;
            Object r = eval(b.right(), row);
            if (Boolean.TRUE.equals(r)) return true;
            if (l == null || r == null) return null;
            return asBoolean(l, b) || asBoolean(r, b);
        }

        Object left = eval(b.left(), row);
        Object right = eval(b.right(), row);
        if (left == null || right == null) return null;

        if (op.isArithmetic()) return arithmetic(left, right, op);
        if (op == Operator.LIKE || op == Operator.NOT_LIKE) {
            boolean m = like(String.valueOf(left), String.valueOf(right));
            return op == Operator.LIKE ? m : !m;
        }
        return compareOp(left, right, op);
    }

    private Object evalIn(InExpr in, Row row) {
        if (in.list() == null) throw new ExecutionException("IN subquery was not resolved before execution");
        if (in.list().isEmpty()) return in.negated();   // x IN () is FALSE and x NOT IN () TRUE, even for NULL x
        Object v = eval(in.operand(), row);
        if (v == null) return null;
        boolean sawNull = false;
        for (Expression e : in.list()) {
            Object item = eval(e, row);
            if (item == null) {
                sawNull = true;
            } else if (compare(v, item) == 0) {
                return !in.negated();
            }
        }
        if (sawNull) return null;            // x IN (1, NULL) is UNKNOWN when x is not 1
        return in.negated();
    }

    private Object evalBetween(BetweenExpr bt, Row row) {
        Object v = eval(bt.operand(), row);
        Object lo = eval(bt.low(), row);
        Object hi = eval(bt.high(), row);
        Boolean geLow = v == null || lo == null ? null : compare(v, lo) >= 0;
        Boolean leHigh = v == null || hi == null ? null : compare(v, hi) <= 0;
        Boolean result;
        if (Boolean.FALSE.equals(geLow) || Boolean.FALSE.equals(leHigh)) result = false;
        else if (geLow == null || leHigh == null) result = null;
        else result = true;
        return result == null ? null : (bt.negated() ? !result : result);
    }

    private Object aggregateValue(AggregateExpr agg, Row row) {
        if (row.hasKey(agg.key())) return row.get(agg.key());
        throw new ExecutionException("Aggregate " + agg.key() + " can only be used in HAVING or with GROUP BY");
    }

    private boolean compareOp(Object left, Object right, Operator op) {
        int cmp = compare(left, right);
        return switch (op) {
            case EQ  -> cmp == 0;
            case NEQ -> cmp != 0;
            case LT  -> cmp <  0;
            case GT  -> cmp >  0;
            case LTE -> cmp <= 0;
            case GTE -> cmp >= 0;
            default  -> throw new ExecutionException("Not a comparison operator: " + op.symbol());
        };
    }

    /** Compares two non-null values after numeric promotion and string-to-timestamp conversion. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    int compare(Object left, Object right) {
        if (left instanceof LocalDateTime && right instanceof String s) right = parseTimestamp(s);
        if (right instanceof LocalDateTime && left instanceof String s) left = parseTimestamp(s);
        if (left instanceof Number l && right instanceof Number r && left.getClass() != right.getClass()) {
            if (l instanceof Double || r instanceof Double) {
                return Double.compare(l.doubleValue(), r.doubleValue());
            }
            return Long.compare(l.longValue(), r.longValue());
        }
        if (!(left instanceof Comparable) || !(right instanceof Comparable)) {
            throw new ExecutionException("Cannot compare values of types %s and %s"
                    .formatted(typeName(left), typeName(right)));
        }
        if (!left.getClass().equals(right.getClass())) {
            throw new ExecutionException("Type mismatch: cannot compare %s (%s) with %s (%s)"
                    .formatted(left, typeName(left), right, typeName(right)));
        }
        return ((Comparable) left).compareTo(right);
    }

    private static Object arithmetic(Object left, Object right, Operator op) {
        if (!(left instanceof Number l) || !(right instanceof Number r)) {
            throw new ExecutionException("Operator %s needs numbers but got %s and %s"
                    .formatted(op.symbol(), typeName(left), typeName(right)));
        }
        if (l instanceof Double || r instanceof Double) {
            double a = l.doubleValue(), b = r.doubleValue();
            return switch (op) {
                case ADD -> a + b;
                case SUB -> a - b;
                case MUL -> a * b;
                case DIV -> {
                    if (b == 0) throw new ExecutionException("Division by zero");
                    yield a / b;
                }
                case MOD -> {
                    if (b == 0) throw new ExecutionException("Division by zero");
                    yield a % b;
                }
                default -> throw new IllegalStateException(op.name());
            };
        }
        long a = l.longValue(), b = r.longValue();
        long res;
        try {
            res = switch (op) {
                case ADD -> Math.addExact(a, b);
                case SUB -> Math.subtractExact(a, b);
                case MUL -> Math.multiplyExact(a, b);
                case DIV -> {
                    if (b == 0) throw new ExecutionException("Division by zero");
                    yield a / b;                     // integer division truncates toward zero
                }
                case MOD -> {
                    if (b == 0) throw new ExecutionException("Division by zero");
                    yield a % b;
                }
                default -> throw new IllegalStateException(op.name());
            };
        } catch (ArithmeticException overflow) {
            throw new ExecutionException("Integer overflow in " + a + " " + op.symbol() + " " + b);
        }
        // INT op INT stays INT when it fits; anything involving BIGINT (or overflowing INT) is BIGINT
        if (l instanceof Integer && r instanceof Integer && res >= Integer.MIN_VALUE && res <= Integer.MAX_VALUE) {
            return (int) res;
        }
        return res;
    }

    /** SQL LIKE: {@code %} matches any sequence, {@code _} one character; case-sensitive. */
    static boolean like(String value, String pattern) {
        Pattern p = LIKE_CACHE.computeIfAbsent(pattern, ExpressionEvaluator::likeToRegex);
        return p.matcher(value).matches();
    }

    private static Pattern likeToRegex(String pattern) {
        StringBuilder re = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            if (c == '%' || c == '_') {
                if (literal.length() > 0) {
                    re.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                re.append(c == '%' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) re.append(Pattern.quote(literal.toString()));
        return Pattern.compile(re.toString(), Pattern.DOTALL);
    }

    private static boolean asBoolean(Object v, Expression context) {
        if (v instanceof Boolean b) return b;
        throw new ExecutionException("Expected a boolean in " + context + " but got " + typeName(v) + " " + v);
    }

    private static String typeName(Object v) {
        return v == null ? "NULL" : switch (v) {
            case Integer i -> "INT";
            case Long l -> "BIGINT";
            case Double d -> "DOUBLE";
            case String s -> "VARCHAR";
            case Boolean b -> "BOOLEAN";
            case LocalDateTime t -> "TIMESTAMP";
            default -> v.getClass().getSimpleName();
        };
    }

    // =========================================================================
    // Column resolution
    // =========================================================================

    private Object resolveColumnValue(String colName, Row row) {
        if (row.hasKey(colName)) {
            return row.get(colName);
        }
        int dot = colName.indexOf('.');
        if (dot > 0) {
            String qualifier = colName.substring(0, dot);
            String shortName = colName.substring(dot + 1);
            // rows not qualified with this name (e.g. in-memory rows): the short name is unambiguous
            boolean qualifiedKeys = row.values().keySet().stream().anyMatch(k -> k.regionMatches(true, 0, qualifier + ".", 0, qualifier.length() + 1));
            if (!qualifiedKeys && row.hasKey(shortName)) {
                return row.get(shortName);
            }
        } else {
            for (Map.Entry<String, Object> entry : row.values().entrySet()) {
                String k = entry.getKey();
                if (k.length() > colName.length() && k.regionMatches(true, k.length() - colName.length() - 1, "." + colName, 0, colName.length() + 1)) {
                    return entry.getValue();
                }
            }
        }
        if (schema == null || !hasColumn(colName)) {
            throw new SchemaException("Column '%s' does not exist".formatted(colName));
        }
        return null;
    }

    private boolean hasColumn(String colName) {
        if (schema.hasColumn(colName)) return true;
        int dot = colName.indexOf('.');
        return dot > 0 && schema.hasColumn(colName.substring(dot + 1));
    }

    private LocalDateTime parseTimestamp(String s) {
        try {
            return TimestampParser.parse(s);   // same forms as INSERT accepts
        } catch (DateTimeParseException e) {
            throw new ExecutionException(
                    "Cannot parse '%s' as TIMESTAMP. Expected format: yyyy-MM-dd HH:mm:ss".formatted(s));
        }
    }
}
