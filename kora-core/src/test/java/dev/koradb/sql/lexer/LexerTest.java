package dev.koradb.sql.lexer;

import dev.koradb.exception.ParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.koradb.sql.lexer.TokenType.AND;
import static dev.koradb.sql.lexer.TokenType.BIGINT;
import static dev.koradb.sql.lexer.TokenType.BOOLEAN;
import static dev.koradb.sql.lexer.TokenType.COMMA;
import static dev.koradb.sql.lexer.TokenType.CREATE;
import static dev.koradb.sql.lexer.TokenType.DATABASE;
import static dev.koradb.sql.lexer.TokenType.DELETE;
import static dev.koradb.sql.lexer.TokenType.DOUBLE;
import static dev.koradb.sql.lexer.TokenType.EOF;
import static dev.koradb.sql.lexer.TokenType.EQ;
import static dev.koradb.sql.lexer.TokenType.FALSE;
import static dev.koradb.sql.lexer.TokenType.FROM;
import static dev.koradb.sql.lexer.TokenType.GT;
import static dev.koradb.sql.lexer.TokenType.IDENTIFIER;
import static dev.koradb.sql.lexer.TokenType.INDEX;
import static dev.koradb.sql.lexer.TokenType.INSERT;
import static dev.koradb.sql.lexer.TokenType.INT;
import static dev.koradb.sql.lexer.TokenType.INTO;
import static dev.koradb.sql.lexer.TokenType.LITERAL_DOUBLE;
import static dev.koradb.sql.lexer.TokenType.LITERAL_INT;
import static dev.koradb.sql.lexer.TokenType.LITERAL_LONG;
import static dev.koradb.sql.lexer.TokenType.LITERAL_STRING;
import static dev.koradb.sql.lexer.TokenType.LPAREN;
import static dev.koradb.sql.lexer.TokenType.MINUS;
import static dev.koradb.sql.lexer.TokenType.NEQ;
import static dev.koradb.sql.lexer.TokenType.NOT;
import static dev.koradb.sql.lexer.TokenType.ON;
import static dev.koradb.sql.lexer.TokenType.OR;
import static dev.koradb.sql.lexer.TokenType.PLUS;
import static dev.koradb.sql.lexer.TokenType.RPAREN;
import static dev.koradb.sql.lexer.TokenType.SELECT;
import static dev.koradb.sql.lexer.TokenType.SEMICOLON;
import static dev.koradb.sql.lexer.TokenType.SET;
import static dev.koradb.sql.lexer.TokenType.SLASH;
import static dev.koradb.sql.lexer.TokenType.STAR;
import static dev.koradb.sql.lexer.TokenType.TABLE;
import static dev.koradb.sql.lexer.TokenType.TIMESTAMP;
import static dev.koradb.sql.lexer.TokenType.TRUE;
import static dev.koradb.sql.lexer.TokenType.UPDATE;
import static dev.koradb.sql.lexer.TokenType.USE;
import static dev.koradb.sql.lexer.TokenType.VALUES;
import static dev.koradb.sql.lexer.TokenType.VARCHAR;
import static dev.koradb.sql.lexer.TokenType.WHERE;
import static dev.koradb.sql.lexer.TokenType.BEGIN;
import static dev.koradb.sql.lexer.TokenType.COMMIT;
import static dev.koradb.sql.lexer.TokenType.DROP;
import static dev.koradb.sql.lexer.TokenType.ROLLBACK;
import static dev.koradb.sql.lexer.TokenType.LTE;
import static dev.koradb.sql.lexer.TokenType.GTE;
import static dev.koradb.sql.lexer.TokenType.DOT;
import static dev.koradb.sql.lexer.TokenType.LT;
import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the {@link Lexer}.
 *
 * <p>Organization:
 * <ul>
 *   <li>Keywords — every keyword recognized correctly, case-insensitive</li>
 *   <li>Literals — INT, LONG, DOUBLE, STRING, BOOLEAN</li>
 *   <li>Operators — single and two-character</li>
 *   <li>Punctuation — parens, comma, semicolon</li>
 *   <li>Identifiers — names not matching any keyword</li>
 *   <li>Comments — single-line -- stripped</li>
 *   <li>Full statements — realistic multi-token SQL</li>
 *   <li>Error cases — unterminated string, unknown char, etc.</li>
 * </ul>
 */
@DisplayName("Lexer")
class LexerTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Tokenize and strip the trailing EOF for cleaner assertions. */
    private List<Token> lex(String sql) {
        List<Token> all = new Lexer(sql).tokenize();
        return all.subList(0, all.size() - 1); // drop EOF
    }

    private void assertTypes(String sql, TokenType... expected) {
        List<TokenType> actual = lex(sql).stream().map(Token::type).toList();
        assertThat(actual).containsExactly(expected);
    }

    private void assertLexemes(String sql, String... expected) {
        List<String> actual = lex(sql).stream().map(Token::lexeme).toList();
        assertThat(actual).containsExactly(expected);
    }

    // =========================================================================
    // Keywords
    // =========================================================================

    @Nested
    @DisplayName("Keywords")
    class Keywords {

        @Test
        @DisplayName("recognises DDL keywords (uppercase)")
        void ddlUpperCase() {
            assertTypes("CREATE DROP DATABASE TABLE INDEX ON USE",
                    CREATE, DROP, DATABASE, TABLE, INDEX, ON, USE);
        }

        @Test
        @DisplayName("recognises DML keywords (uppercase)")
        void dmlUpperCase() {
            assertTypes("INSERT INTO VALUES SELECT FROM WHERE UPDATE SET DELETE",
                    INSERT, INTO, VALUES, SELECT, FROM, WHERE, UPDATE, SET, DELETE);
        }

        @Test
        @DisplayName("keywords are case-insensitive — lowercase")
        void keywordsLowerCase() {
            assertTypes("select from where", SELECT, FROM, WHERE);
        }

        @Test
        @DisplayName("keywords are case-insensitive — mixed case")
        void keywordsMixedCase() {
            assertTypes("SeLeCt FrOm", SELECT, FROM);
        }

        @Test
        @DisplayName("keyword lexemes are always uppercase")
        void keywordLexemesUpperCase() {
            assertLexemes("select", "SELECT");
        }

        @Test
        @DisplayName("data type keywords recognised")
        void dataTypes() {
            assertTypes("INT BIGINT BOOLEAN VARCHAR DOUBLE TIMESTAMP",
                    INT, BIGINT, BOOLEAN, VARCHAR, DOUBLE, TIMESTAMP);
        }

        @Test
        @DisplayName("boolean keywords TRUE and FALSE recognised")
        void booleanKeywords() {
            assertTypes("TRUE FALSE true false", TRUE, FALSE, TRUE, FALSE);
        }

        @Test
        @DisplayName("logical AND OR NOT recognised")
        void logicalKeywords() {
            assertTypes("AND OR NOT", AND, OR, NOT);
        }

        @Test
        @DisplayName("transaction keywords recognised")
        void transactionKeywords() {
            assertTypes("BEGIN COMMIT ROLLBACK", BEGIN, COMMIT, ROLLBACK);
        }
    }

    // =========================================================================
    // Literals
    // =========================================================================

    @Nested
    @DisplayName("Literals")
    class Literals {

        @Test
        @DisplayName("integer literal — positive")
        void intLiteral() {
            List<Token> tokens = lex("42");
            assertThat(tokens).hasSize(1);
            assertThat(tokens.get(0).type()).isEqualTo(LITERAL_INT);
            assertThat(tokens.get(0).lexeme()).isEqualTo("42");
        }

        @Test
        @DisplayName("integer literal — zero")
        void intLiteralZero() {
            assertTypes("0", LITERAL_INT);
        }

        @Test
        @DisplayName("integer literal — INT_MAX")
        void intMax() {
            assertTypes(String.valueOf(Integer.MAX_VALUE), LITERAL_INT);
        }

        @Test
        @DisplayName("long literal — value exceeds INT_MAX")
        void longLiteral() {
            long bigValue = (long) Integer.MAX_VALUE + 1;
            List<Token> tokens = lex(String.valueOf(bigValue));
            assertThat(tokens.get(0).type()).isEqualTo(LITERAL_LONG);
            assertThat(tokens.get(0).lexeme()).isEqualTo(String.valueOf(bigValue));
        }

        @Test
        @DisplayName("double literal — with decimal point")
        void doubleLiteral() {
            List<Token> tokens = lex("3.14");
            assertThat(tokens.get(0).type()).isEqualTo(LITERAL_DOUBLE);
            assertThat(tokens.get(0).lexeme()).isEqualTo("3.14");
        }

        @Test
        @DisplayName("double literal — leading digit before decimal")
        void doubleLeading() {
            assertTypes("0.5", LITERAL_DOUBLE);
        }

        @Test
        @DisplayName("string literal — simple")
        void stringLiteral() {
            List<Token> tokens = lex("'hello'");
            assertThat(tokens.get(0).type()).isEqualTo(LITERAL_STRING);
            // Lexeme strips the surrounding single quotes
            assertThat(tokens.get(0).lexeme()).isEqualTo("hello");
        }

        @Test
        @DisplayName("string literal — empty")
        void emptyString() {
            List<Token> tokens = lex("''");
            assertThat(tokens.get(0).type()).isEqualTo(LITERAL_STRING);
            assertThat(tokens.get(0).lexeme()).isEqualTo("");
        }

        @Test
        @DisplayName("string literal — contains spaces")
        void stringWithSpaces() {
            List<Token> tokens = lex("'Alice Smith'");
            assertThat(tokens.get(0).lexeme()).isEqualTo("Alice Smith");
        }

        @Test
        @DisplayName("string literal — resembles timestamp")
        void timestampString() {
            List<Token> tokens = lex("'2024-01-15 10:00:00'");
            assertThat(tokens.get(0).type()).isEqualTo(LITERAL_STRING);
            assertThat(tokens.get(0).lexeme()).isEqualTo("2024-01-15 10:00:00");
        }
    }

    // =========================================================================
    // Operators
    // =========================================================================

    @Nested
    @DisplayName("Operators")
    class Operators {

        @Test
        @DisplayName("single char operators: + - * /")
        void singleCharArithmetic() {
            assertTypes("+ - * /", PLUS, MINUS, STAR, SLASH);
        }

        @Test
        @DisplayName("comparison: = < >")
        void basicComparisons() {
            assertTypes("= < >", EQ, LT, GT);
        }

        @Test
        @DisplayName("two-char comparisons: != <= >=")
        void twoCharComparisons() {
            assertTypes("!= <= >=", NEQ, LTE, GTE);
        }

        @Test
        @DisplayName("star is distinguished from multiplication in SELECT *")
        void starInSelect() {
            assertTypes("SELECT *", SELECT, STAR);
        }
    }

    // =========================================================================
    // Punctuation
    // =========================================================================

    @Nested
    @DisplayName("Punctuation")
    class Punctuation {

        @Test
        @DisplayName("parentheses, comma, semicolon, dot")
        void allPunctuation() {
            assertTypes("( ) , ; .", LPAREN, RPAREN, COMMA, SEMICOLON, DOT);
        }
    }

    // =========================================================================
    // Identifiers
    // =========================================================================

    @Nested
    @DisplayName("Identifiers")
    class Identifiers {

        @Test
        @DisplayName("simple identifier preserves original casing")
        void simpleIdentifier() {
            List<Token> tokens = lex("users");
            assertThat(tokens.get(0).type()).isEqualTo(IDENTIFIER);
            assertThat(tokens.get(0).lexeme()).isEqualTo("users");
        }

        @Test
        @DisplayName("identifier with underscore")
        void underscoreIdentifier() {
            List<Token> tokens = lex("user_id");
            assertThat(tokens.get(0).type()).isEqualTo(IDENTIFIER);
            assertThat(tokens.get(0).lexeme()).isEqualTo("user_id");
        }

        @Test
        @DisplayName("identifier starting with underscore")
        void leadingUnderscore() {
            assertTypes("_private", IDENTIFIER);
        }

        @Test
        @DisplayName("identifier with mixed case preserved")
        void mixedCaseIdentifier() {
            List<Token> tokens = lex("firstName");
            assertThat(tokens.get(0).lexeme()).isEqualTo("firstName");
        }

        @Test
        @DisplayName("words that start like a keyword but are longer become identifiers")
        void nearKeywordIdentifier() {
            // "CREATED" is not a keyword — it should be an IDENTIFIER
            assertTypes("created", IDENTIFIER);
        }
    }

    // =========================================================================
    // Comments
    // =========================================================================

    @Nested
    @DisplayName("Comments")
    class Comments {

        @Test
        @DisplayName("single-line -- comment is skipped entirely")
        void singleLineComment() {
            List<Token> tokens = lex("-- this is a comment\nSELECT");
            assertThat(tokens).hasSize(1);
            assertThat(tokens.get(0).type()).isEqualTo(SELECT);
        }

        @Test
        @DisplayName("inline comment skips to end of line")
        void inlineComment() {
            List<Token> tokens = lex("SELECT -- pick all\n* FROM users");
            assertTypes("SELECT -- pick all\n* FROM users", SELECT, STAR, FROM, IDENTIFIER);
        }
    }

    // =========================================================================
    // Line tracking
    // =========================================================================

    @Nested
    @DisplayName("Line tracking")
    class LineTracking {

        @Test
        @DisplayName("tokens on line 1 report line=1")
        void lineOne() {
            List<Token> tokens = lex("SELECT");
            assertThat(tokens.get(0).line()).isEqualTo(1);
        }

        @Test
        @DisplayName("token on second line reports line=2")
        void lineTwo() {
            List<Token> tokens = lex("SELECT\nFROM");
            assertThat(tokens.get(1).line()).isEqualTo(2);
        }
    }

    // =========================================================================
    // Full statements
    // =========================================================================

    @Nested
    @DisplayName("Full SQL statements")
    class FullStatements {

        @Test
        @DisplayName("CREATE TABLE statement")
        void createTable() {
            assertTypes(
                    "CREATE TABLE users ( id INT , name VARCHAR )",
                    CREATE, TABLE, IDENTIFIER, LPAREN,
                    IDENTIFIER, INT, COMMA,
                    IDENTIFIER, VARCHAR, RPAREN);
        }

        @Test
        @DisplayName("INSERT statement")
        void insertStatement() {
            assertTypes(
                    "INSERT INTO users VALUES (1, 'Alice', 30)",
                    INSERT, INTO, IDENTIFIER, VALUES,
                    LPAREN, LITERAL_INT, COMMA, LITERAL_STRING, COMMA, LITERAL_INT, RPAREN);
        }

        @Test
        @DisplayName("SELECT with WHERE")
        void selectWithWhere() {
            assertTypes(
                    "SELECT name FROM users WHERE age > 20",
                    SELECT, IDENTIFIER, FROM, IDENTIFIER, WHERE, IDENTIFIER, GT, LITERAL_INT);
        }

        @Test
        @DisplayName("UPDATE statement")
        void updateStatement() {
            assertTypes(
                    "UPDATE users SET age = 31 WHERE id = 1",
                    UPDATE, IDENTIFIER, SET, IDENTIFIER, EQ, LITERAL_INT,
                    WHERE, IDENTIFIER, EQ, LITERAL_INT);
        }

        @Test
        @DisplayName("DELETE statement")
        void deleteStatement() {
            assertTypes(
                    "DELETE FROM users WHERE id = 1",
                    DELETE, FROM, IDENTIFIER, WHERE, IDENTIFIER, EQ, LITERAL_INT);
        }

        @Test
        @DisplayName("EOF token is always last")
        void eofAlwaysLast() {
            List<Token> all = new Lexer("SELECT *").tokenize();
            assertThat(all.getLast().type()).isEqualTo(EOF);
        }

        @Test
        @DisplayName("empty input produces only EOF")
        void emptyInput() {
            List<Token> all = new Lexer("").tokenize();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).type()).isEqualTo(EOF);
        }

        @Test
        @DisplayName("whitespace-only input produces only EOF")
        void whitespaceOnly() {
            List<Token> all = new Lexer("   \n\t  ").tokenize();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).type()).isEqualTo(EOF);
        }
    }

    // =========================================================================
    // Error cases
    // =========================================================================

    @Nested
    @DisplayName("Error cases")
    class ErrorCases {

        @Test
        @DisplayName("unterminated string literal throws ParseException")
        void unterminatedString() {
            assertThatThrownBy(() -> new Lexer("'hello").tokenize())
                    .isInstanceOf(ParseException.class)
                    .hasMessageContaining("Unterminated string literal");
        }

        @Test
        @DisplayName("unterminated string reports correct line")
        void unterminatedStringLine() {
            assertThatThrownBy(() -> new Lexer("\n\n'oops").tokenize())
                    .isInstanceOf(ParseException.class)
                    .satisfies(e -> assertThat(((ParseException) e).getLine()).isEqualTo(3));
        }

        @Test
        @DisplayName("unknown character @ throws ParseException")
        void unknownCharacter() {
            assertThatThrownBy(() -> new Lexer("SELECT @name").tokenize())
                    .isInstanceOf(ParseException.class)
                    .hasMessageContaining("Unexpected character");
        }

        @Test
        @DisplayName("bare ! without = throws ParseException")
        void bareExclamation() {
            assertThatThrownBy(() -> new Lexer("id ! 1").tokenize())
                    .isInstanceOf(ParseException.class)
                    .hasMessageContaining("!=");
        }

        @Test
        @DisplayName("null input throws IllegalArgumentException")
        void nullInput() {
            assertThatThrownBy(() -> new Lexer(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
