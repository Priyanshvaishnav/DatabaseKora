package dev.koradb.exception;

/**
 * Thrown when the {@link dev.koradb.sql.lexer.Lexer} or
 * {@link dev.koradb.sql.parser.Parser} encounter invalid SQL.
 *
 * <p>Always includes a human-readable message with the line number.
 */
public class ParseException extends KoraException {

    private final int line;

    public ParseException(String message, int line) {
        super("Line %d: %s".formatted(line, message));
        this.line = line;
    }

    public ParseException(String message, int line, Throwable cause) {
        super("Line %d: %s".formatted(line, message), cause);
        this.line = line;
    }

    public int getLine() {
        return line;
    }
}
