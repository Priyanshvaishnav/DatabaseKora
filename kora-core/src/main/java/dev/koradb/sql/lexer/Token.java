package dev.koradb.sql.lexer;

/**
 * An immutable token produced by the {@link Lexer}.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code type}   — what kind of token this is.</li>
 *   <li>{@code lexeme} — the exact text from the source (keyword in uppercase, string without quotes).</li>
 *   <li>{@code line}   — 1-based source line; used for error messages.</li>
 * </ul>
 *
 * <p>Examples:
 * <pre>
 *   SELECT   → Token(SELECT,         "SELECT",  1)
 *   42       → Token(LITERAL_INT,    "42",      1)
 *   'Alice'  → Token(LITERAL_STRING, "Alice",   1)
 *   >=       → Token(GTE,            ">=",      1)
 * </pre>
 */
public record Token(TokenType type, String lexeme, int line) {

    /**
     * Convenience factory for the synthetic EOF token.
     */
    public static Token eof(int line) {
        return new Token(TokenType.EOF, "", line);
    }

    @Override
    public String toString() {
        return "Token(%s, \"%s\", line=%d)".formatted(type, lexeme, line);
    }
}
