package dev.koradb.sql.lexer;

import dev.koradb.exception.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts a raw SQL string into a flat list of {@link Token} objects.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>Single-pass, character-by-character scanner — no backtracking.</li>
 *   <li>Keywords are matched case-insensitively; stored in the Token with
 *       their canonical uppercase spelling.</li>
 *   <li>String literals are delimited by single quotes {@code '}.
 *       The lexeme stored in the Token is the content <em>without</em> quotes.</li>
 *   <li>Numeric literals: if a number contains a {@code .} it becomes
 *       {@code LITERAL_DOUBLE}; values fitting in {@code int} become
 *       {@code LITERAL_INT}; larger values become {@code LITERAL_LONG}.</li>
 *   <li>A synthetic {@code EOF} token is appended so the Parser never
 *       reads past the end of the list.</li>
 * </ul>
 *
 * <h2>What the Lexer does NOT do</h2>
 * <ul>
 *   <li>It does not validate grammar — that is the Parser's job.</li>
 *   <li>It does not support block comments ({@code / * ... * /}) in Milestone 1;
 *       only single-line ({@code --}) comments are stripped.</li>
 * </ul>
 */
public final class Lexer {

    private static final Logger log = LoggerFactory.getLogger(Lexer.class);

    /** Maps uppercase keyword strings to their corresponding TokenType. */
    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("CREATE",    TokenType.CREATE),
            Map.entry("DROP",      TokenType.DROP),
            Map.entry("DATABASE",  TokenType.DATABASE),
            Map.entry("TABLE",     TokenType.TABLE),
            Map.entry("INDEX",     TokenType.INDEX),
            Map.entry("UNIQUE",    TokenType.UNIQUE),
            Map.entry("ON",        TokenType.ON),
            Map.entry("USE",       TokenType.USE),
            Map.entry("INSERT",    TokenType.INSERT),
            Map.entry("INTO",      TokenType.INTO),
            Map.entry("VALUES",    TokenType.VALUES),
            Map.entry("SELECT",    TokenType.SELECT),
            Map.entry("FROM",      TokenType.FROM),
            Map.entry("WHERE",     TokenType.WHERE),
            Map.entry("UPDATE",    TokenType.UPDATE),
            Map.entry("SET",       TokenType.SET),
            Map.entry("DELETE",    TokenType.DELETE),
            Map.entry("ORDER",     TokenType.ORDER),
            Map.entry("BY",        TokenType.BY),
            Map.entry("ASC",       TokenType.ASC),
            Map.entry("DESC",      TokenType.DESC),
            Map.entry("LIMIT",     TokenType.LIMIT),
            Map.entry("OFFSET",    TokenType.OFFSET),
            Map.entry("JOIN",      TokenType.JOIN),
            Map.entry("INNER",     TokenType.INNER),
            Map.entry("GROUP",     TokenType.GROUP),
            Map.entry("AS",        TokenType.AS),
            Map.entry("COUNT",     TokenType.COUNT),
            Map.entry("SUM",       TokenType.SUM),
            Map.entry("AVG",       TokenType.AVG),
            Map.entry("MIN",       TokenType.MIN),
            Map.entry("MAX",       TokenType.MAX),
            Map.entry("BEGIN",     TokenType.BEGIN),
            Map.entry("COMMIT",    TokenType.COMMIT),
            Map.entry("ROLLBACK",  TokenType.ROLLBACK),
            Map.entry("EXPLAIN",   TokenType.EXPLAIN),
            Map.entry("INT",       TokenType.INT),
            Map.entry("BIGINT",    TokenType.BIGINT),
            Map.entry("BOOLEAN",   TokenType.BOOLEAN),
            Map.entry("VARCHAR",   TokenType.VARCHAR),
            Map.entry("DOUBLE",    TokenType.DOUBLE),
            Map.entry("TIMESTAMP", TokenType.TIMESTAMP),
            Map.entry("TRUE",      TokenType.TRUE),
            Map.entry("FALSE",     TokenType.FALSE),
            Map.entry("AND",       TokenType.AND),
            Map.entry("OR",        TokenType.OR),
            Map.entry("NOT",       TokenType.NOT)
    );

    private final String source;
    private final List<Token> tokens = new ArrayList<>();

    private int start   = 0;   // index of first char of the current lexeme
    private int current = 0;   // index of the next char to consume
    private int line    = 1;   // 1-based line counter

    public Lexer(String source) {
        if (source == null) {
            throw new IllegalArgumentException("SQL source must not be null");
        }
        this.source = source;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Runs the full scan and returns the immutable token list.
     *
     * @return list of tokens ending with an {@link TokenType#EOF} token
     * @throws ParseException if the source contains an illegal character or
     *                        an unterminated string literal
     */
    public List<Token> tokenize() {
        log.debug("Tokenizing {} character(s) of SQL", source.length());

        while (!isAtEnd()) {
            start = current;
            scanToken();
        }

        tokens.add(Token.eof(line));
        log.debug("Produced {} token(s)", tokens.size());
        return List.copyOf(tokens);
    }

    // =========================================================================
    // Core scanner loop
    // =========================================================================

    private void scanToken() {
        char c = advance();

        switch (c) {
            case '('  -> addToken(TokenType.LPAREN,    "(");
            case ')'  -> addToken(TokenType.RPAREN,    ")");
            case ','  -> addToken(TokenType.COMMA,     ",");
            case ';'  -> addToken(TokenType.SEMICOLON, ";");
            case '.'  -> addToken(TokenType.DOT,       ".");
            case '+'  -> addToken(TokenType.PLUS,      "+");
            case '/'  -> addToken(TokenType.SLASH,     "/");
            case '*'  -> addToken(TokenType.STAR,      "*");

            // Minus: could start a single-line comment "--"
            case '-'  -> {
                if (match('-')) {
                    // Skip the rest of the line
                    while (!isAtEnd() && peek() != '\n') advance();
                } else {
                    addToken(TokenType.MINUS, "-");
                }
            }

            // Two-character operators: = != < > <= >=
            case '='  -> addToken(TokenType.EQ,  "=");
            case '!'  -> {
                if (match('=')) {
                    addToken(TokenType.NEQ, "!=");
                } else {
                    throw new ParseException(
                            "Unexpected character '!'. Did you mean '!='?", line);
                }
            }
            case '<'  -> {
                if (match('=')) {
                    addToken(TokenType.LTE, "<=");
                } else {
                    addToken(TokenType.LT, "<");
                }
            }
            case '>'  -> {
                if (match('=')) {
                    addToken(TokenType.GTE, ">=");
                } else {
                    addToken(TokenType.GT, ">");
                }
            }

            // Whitespace
            case ' ', '\r', '\t' -> { /* ignore */ }
            case '\n' -> line++;

            // String literals
            case '\'' -> scanStringLiteral();

            default -> {
                if (isDigit(c)) {
                    scanNumericLiteral();
                } else if (isAlpha(c)) {
                    scanIdentifierOrKeyword();
                } else {
                    throw new ParseException(
                            "Unexpected character: '%c' (0x%02X)".formatted(c, (int) c), line);
                }
            }
        }
    }

    // =========================================================================
    // Literal scanners
    // =========================================================================

    /**
     * Scans a single-quoted string literal.
     * The produced token's lexeme is the content WITHOUT surrounding quotes.
     * Escaped single quotes ({@code ''}) are not supported in Milestone 1.
     */
    private void scanStringLiteral() {
        int startLine = line;

        while (!isAtEnd() && peek() != '\'') {
            if (peek() == '\n') line++;
            advance();
        }

        if (isAtEnd()) {
            throw new ParseException("Unterminated string literal", startLine);
        }

        // Consume the closing quote
        advance();

        // Lexeme is the content between the quotes
        String content = source.substring(start + 1, current - 1);
        addToken(TokenType.LITERAL_STRING, content);
    }

    /**
     * Scans an integer or floating-point literal.
     * Decides between LITERAL_INT, LITERAL_LONG, and LITERAL_DOUBLE.
     */
    private void scanNumericLiteral() {
        while (!isAtEnd() && isDigit(peek())) advance();

        boolean isDouble = false;
        if (!isAtEnd() && peek() == '.' && isDigit(peekNext())) {
            isDouble = true;
            advance(); // consume '.'
            while (!isAtEnd() && isDigit(peek())) advance();
        }

        String text = source.substring(start, current);

        if (isDouble) {
            addToken(TokenType.LITERAL_DOUBLE, text);
        } else {
            // Decide INT vs LONG based on magnitude
            try {
                Integer.parseInt(text);
                addToken(TokenType.LITERAL_INT, text);
            } catch (NumberFormatException e) {
                try {
                    Long.parseLong(text);
                    addToken(TokenType.LITERAL_LONG, text);
                } catch (NumberFormatException ex) {
                    throw new ParseException(
                            "Numeric literal too large: " + text, line);
                }
            }
        }
    }

    /**
     * Scans an identifier or keyword.
     * Comparison is case-insensitive; keyword tokens carry the uppercase
     * canonical spelling; identifiers carry the original casing.
     */
    private void scanIdentifierOrKeyword() {
        while (!isAtEnd() && isAlphaNumeric(peek())) advance();

        String text     = source.substring(start, current);
        String upper    = text.toUpperCase();
        TokenType type  = KEYWORDS.getOrDefault(upper, TokenType.IDENTIFIER);

        // For keywords, store the canonical uppercase form.
        // For identifiers, preserve original casing (table/column names are case-sensitive).
        if (type == TokenType.IDENTIFIER) {
            addToken(type, text);
        } else {
            addToken(type, upper);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Consume and return the current character, advancing the cursor. */
    private char advance() {
        return source.charAt(current++);
    }

    /**
     * Peek at the current character without consuming it.
     * Returns {@code '\0'} at end of input.
     */
    private char peek() {
        return isAtEnd() ? '\0' : source.charAt(current);
    }

    /**
     * Peek one character ahead without consuming.
     * Returns {@code '\0'} at or past end of input.
     */
    private char peekNext() {
        return (current + 1 >= source.length()) ? '\0' : source.charAt(current + 1);
    }

    /**
     * If the current character equals {@code expected}, consume it and return true.
     * Otherwise return false without consuming.
     */
    private boolean match(char expected) {
        if (isAtEnd() || source.charAt(current) != expected) return false;
        current++;
        return true;
    }

    private boolean isAtEnd() {
        return current >= source.length();
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }

    private void addToken(TokenType type, String lexeme) {
        tokens.add(new Token(type, lexeme, line));
    }
}
