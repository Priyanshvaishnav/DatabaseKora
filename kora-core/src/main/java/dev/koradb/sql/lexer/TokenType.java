package dev.koradb.sql.lexer;

/**
 * Every distinct symbol or keyword the Lexer can produce.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Keywords are matched case-insensitively by the Lexer; they are stored here in uppercase.</li>
 *   <li>LITERAL_* variants carry their raw text as the lexeme in the Token.</li>
 *   <li>EOF is a synthetic token appended by the Lexer so the Parser never reads past the end.</li>
 * </ul>
 */
public enum TokenType {

    // -------------------------------------------------------------------------
    // Keywords — DDL
    // -------------------------------------------------------------------------
    CREATE,
    DROP,
    DATABASE,
    TABLE,
    INDEX,
    UNIQUE,
    ON,
    USE,

    // -------------------------------------------------------------------------
    // Keywords — DML & Query
    // -------------------------------------------------------------------------
    INSERT,
    INTO,
    VALUES,
    SELECT,
    FROM,
    WHERE,
    UPDATE,
    SET,
    DELETE,
    ORDER,
    BY,
    ASC,
    DESC,
    LIMIT,
    OFFSET,
    JOIN,
    INNER,
    GROUP,
    AS,
    COUNT,
    SUM,
    AVG,
    MIN,
    MAX,

    // -------------------------------------------------------------------------
    // Keywords — Transactions (placeholder for Phase 7)
    // -------------------------------------------------------------------------
    BEGIN,
    COMMIT,
    ROLLBACK,
    TRANSACTION,

    // -------------------------------------------------------------------------
    // Keywords — Query
    // -------------------------------------------------------------------------
    EXPLAIN,
    ANALYZE,

    // -------------------------------------------------------------------------
    // Data types
    // -------------------------------------------------------------------------
    INT,
    BIGINT,
    BOOLEAN,
    VARCHAR,
    DOUBLE,
    TIMESTAMP,

    // -------------------------------------------------------------------------
    // Boolean literals
    // -------------------------------------------------------------------------
    TRUE,
    FALSE,

    // -------------------------------------------------------------------------
    // Logical connectives
    // -------------------------------------------------------------------------
    AND,
    OR,
    NOT,

    // -------------------------------------------------------------------------
    // Comparison operators
    // -------------------------------------------------------------------------
    EQ,       // =
    NEQ,      // !=
    LT,       // <
    GT,       // >
    LTE,      // <=
    GTE,      // >=

    // -------------------------------------------------------------------------
    // Arithmetic (future use)
    // -------------------------------------------------------------------------
    PLUS,     // +
    MINUS,    // -
    STAR,     // *   (also used in SELECT *)
    SLASH,    // /

    // -------------------------------------------------------------------------
    // Punctuation
    // -------------------------------------------------------------------------
    LPAREN,   // (
    RPAREN,   // )
    COMMA,    // ,
    SEMICOLON,// ;
    DOT,      // .

    // -------------------------------------------------------------------------
    // Literals
    // -------------------------------------------------------------------------
    LITERAL_INT,      // e.g. 42
    LITERAL_LONG,     // e.g. 9999999999
    LITERAL_DOUBLE,   // e.g. 3.14
    LITERAL_STRING,   // e.g. 'hello'   (lexeme is content WITHOUT quotes)
    LITERAL_BOOLEAN,  // true | false   (redundant with TRUE/FALSE but kept for clarity)

    // -------------------------------------------------------------------------
    // Identifiers — any unquoted name that isn't a keyword
    // -------------------------------------------------------------------------
    IDENTIFIER,

    // -------------------------------------------------------------------------
    // Synthetic — end of input
    // -------------------------------------------------------------------------
    EOF
}
