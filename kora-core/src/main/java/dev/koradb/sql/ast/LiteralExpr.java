package dev.koradb.sql.ast;

/**
 * A constant value in a SQL expression — the right-hand side of a comparison
 * or a value in a VALUES list.
 *
 * <p>The {@code value} field holds the Java representation of the literal:
 * <ul>
 *   <li>SQL {@code 42}         → {@link Integer}</li>
 *   <li>SQL {@code 9999999999} → {@link Long}</li>
 *   <li>SQL {@code 3.14}       → {@link Double}</li>
 *   <li>SQL {@code 'hello'}    → {@link String}</li>
 *   <li>SQL {@code true}       → {@link Boolean}</li>
 *   <li>SQL {@code NULL}       → {@code null}</li>
 * </ul>
 *
 * <p>Timestamp strings are stored as {@link String} and resolved to
 * {@link java.time.LocalDateTime} during execution when the column type
 * is known.
 *
 * @param value the Java-typed literal value; may be null for SQL NULL
 */
public record LiteralExpr(Object value) implements Expression {

    @Override
    public String toString() {
        return value == null ? "NULL" : "Literal(%s)".formatted(value);
    }
}
