package dev.koradb.sql.parser;

import dev.koradb.exception.ParseException;
import dev.koradb.sql.ast.*;
import dev.koradb.sql.lexer.Token;
import dev.koradb.sql.lexer.TokenType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static dev.koradb.sql.lexer.TokenType.*;

/**
 * Recursive-descent parser: converts a flat token list into a typed
 * {@link Statement} AST node.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>One public method {@link #parse()} per call — parses exactly one statement.</li>
 *   <li>One private method per grammar rule, named after that rule.</li>
 *   <li>The parser peeks at the current token to decide which rule to apply,
 *       and consumes tokens by calling {@link #consume(TokenType, String)}.</li>
 *   <li>No backtracking: every branch is determined by the next token (LL(1)).</li>
 *   <li>On error: throws {@link ParseException} with the line number of the
 *       offending token.</li>
 * </ul>
 *
 * <h2>Grammar entry points</h2>
 * <pre>
 *   statement → createDatabase | dropDatabase | useDatabase
 *             | createTable    | dropTable
 *             | insert | select | update | delete
 * </pre>
 *
 * <h2>Not supported in Milestone 1</h2>
 * JOINs, subqueries, aggregates, ORDER BY, GROUP BY, LIMIT, OR/NOT in WHERE,
 * column-list INSERT, expressions in SELECT list.
 */
public final class Parser {

    private static final Logger log = LoggerFactory.getLogger(Parser.class);

    private final List<Token> tokens;
    private int current = 0;

    public Parser(List<Token> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            throw new IllegalArgumentException("Token list must not be null or empty");
        }
        this.tokens = tokens;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Parses one SQL statement from the token stream.
     *
     * @return the parsed {@link Statement} AST node
     * @throws ParseException if the token stream does not match any valid statement
     */
    public Statement parse() {
        log.debug("Parsing {} token(s)", tokens.size());

        // Skip leading semicolons (empty statements)
        while (check(SEMICOLON)) advance();

        Statement stmt = statement();

        // Optional trailing semicolon
        if (check(SEMICOLON)) advance();

        // Must be at EOF — nothing left unconsumed
        if (!isAtEnd()) {
            Token unexpected = peek();
            throw new ParseException(
                    "Unexpected token after statement: " + unexpected.lexeme(),
                    unexpected.line());
        }

        return stmt;
    }

    // =========================================================================
    // Statement dispatch
    // =========================================================================

    private Statement statement() {
        Token token = peek();

        return switch (token.type()) {
            case CREATE   -> parseCreate();
            case DROP     -> parseDrop();
            case USE      -> parseUseDatabase();
            case INSERT   -> parseInsert();
            case SELECT   -> parseSelect();
            case UPDATE   -> parseUpdate();
            case DELETE   -> parseDelete();
            default       -> throw new ParseException(
                    "Expected a SQL statement but found: '" + token.lexeme() + "'",
                    token.line());
        };
    }

    // =========================================================================
    // DDL — CREATE
    // =========================================================================

    /**
     * Dispatches CREATE DATABASE | CREATE TABLE.
     */
    private Statement parseCreate() {
        consume(CREATE, "CREATE");

        Token next = peek();
        return switch (next.type()) {
            case DATABASE -> parseCreateDatabase();
            case TABLE    -> parseCreateTable();
            default       -> throw new ParseException(
                    "Expected DATABASE or TABLE after CREATE, found: '" + next.lexeme() + "'",
                    next.line());
        };
    }

    /**
     * {@code CREATE DATABASE <name>}
     */
    private CreateDatabaseStmt parseCreateDatabase() {
        consume(DATABASE, "DATABASE");
        String name = consumeIdentifier("database name");
        return new CreateDatabaseStmt(name);
    }

    /**
     * {@code CREATE TABLE <name> ( <col> <type> [, ...] )}
     */
    private CreateTableStmt parseCreateTable() {
        consume(TABLE, "TABLE");
        String tableName = consumeIdentifier("table name");

        consume(LPAREN, "(");
        List<ColumnDef> columns = new ArrayList<>();

        // At least one column definition required
        columns.add(parseColumnDef());

        while (check(COMMA)) {
            advance(); // consume ','

            // Trailing comma guard: if next token is ')' the user accidentally
            // left a trailing comma — give a clear error
            if (check(RPAREN)) {
                throw new ParseException(
                        "Trailing comma in column list — remove the comma before ')'",
                        peek().line());
            }
            columns.add(parseColumnDef());
        }

        consume(RPAREN, ")");
        return new CreateTableStmt(tableName, columns);
    }

    /**
     * {@code <columnName> <dataType>}
     */
    private ColumnDef parseColumnDef() {
        String   colName  = consumeIdentifier("column name");
        DataType dataType = parseDataType();
        return new ColumnDef(colName, dataType);
    }

    private DataType parseDataType() {
        Token token = advance();
        return switch (token.type()) {
            case INT       -> DataType.INT;
            case BIGINT    -> DataType.BIGINT;
            case BOOLEAN   -> DataType.BOOLEAN;
            case VARCHAR   -> DataType.VARCHAR;
            case DOUBLE    -> DataType.DOUBLE;
            case TIMESTAMP -> DataType.TIMESTAMP;
            default        -> throw new ParseException(
                    "Expected a data type (INT, BIGINT, BOOLEAN, VARCHAR, DOUBLE, TIMESTAMP) " +
                    "but found: '" + token.lexeme() + "'",
                    token.line());
        };
    }

    // =========================================================================
    // DDL — DROP
    // =========================================================================

    private Statement parseDrop() {
        consume(DROP, "DROP");

        Token next = peek();
        return switch (next.type()) {
            case DATABASE -> parseDropDatabase();
            case TABLE    -> parseDropTable();
            default       -> throw new ParseException(
                    "Expected DATABASE or TABLE after DROP, found: '" + next.lexeme() + "'",
                    next.line());
        };
    }

    private DropDatabaseStmt parseDropDatabase() {
        consume(DATABASE, "DATABASE");
        String name = consumeIdentifier("database name");
        return new DropDatabaseStmt(name);
    }

    private DropTableStmt parseDropTable() {
        consume(TABLE, "TABLE");
        String name = consumeIdentifier("table name");
        return new DropTableStmt(name);
    }

    // =========================================================================
    // USE
    // =========================================================================

    /**
     * {@code USE <databaseName>}
     */
    private UseDatabaseStmt parseUseDatabase() {
        consume(USE, "USE");
        String name = consumeIdentifier("database name");
        return new UseDatabaseStmt(name);
    }

    // =========================================================================
    // DML — INSERT
    // =========================================================================

    /**
     * {@code INSERT INTO <table> VALUES ( <value> [, ...] )}
     */
    private InsertStmt parseInsert() {
        consume(INSERT, "INSERT");
        consume(INTO,   "INTO");
        String tableName = consumeIdentifier("table name");
        consume(VALUES, "VALUES");
        consume(LPAREN, "(");

        List<LiteralExpr> values = new ArrayList<>();
        values.add(parseLiteral());

        while (check(COMMA)) {
            advance();
            values.add(parseLiteral());
        }

        consume(RPAREN, ")");
        return new InsertStmt(tableName, values);
    }

    // =========================================================================
    // DML — SELECT
    // =========================================================================

    /**
     * {@code SELECT (*|col,...) FROM <table> [WHERE <cond>]}
     */
    private SelectStmt parseSelect() {
        consume(SELECT, "SELECT");

        List<String> columns = new ArrayList<>();
        if (check(STAR)) {
            advance(); // SELECT *
        } else {
            columns.add(consumeIdentifier("column name"));
            while (check(COMMA)) {
                advance();
                columns.add(consumeIdentifier("column name"));
            }
        }

        consume(FROM, "FROM");
        String tableName = consumeIdentifier("table name");

        Optional<WhereClause> where = Optional.empty();
        if (check(WHERE)) {
            advance();
            where = Optional.of(parseWhereClause());
        }

        return new SelectStmt(columns, tableName, where);
    }

    // =========================================================================
    // DML — UPDATE
    // =========================================================================

    /**
     * {@code UPDATE <table> SET <col>=<val> [,...] [WHERE <cond>]}
     */
    private UpdateStmt parseUpdate() {
        consume(UPDATE, "UPDATE");
        String tableName = consumeIdentifier("table name");
        consume(SET, "SET");

        List<UpdateStmt.Assignment> assignments = new ArrayList<>();
        assignments.add(parseAssignment());
        while (check(COMMA)) {
            advance();
            assignments.add(parseAssignment());
        }

        Optional<WhereClause> where = Optional.empty();
        if (check(WHERE)) {
            advance();
            where = Optional.of(parseWhereClause());
        }

        return new UpdateStmt(tableName, assignments, where);
    }

    /**
     * {@code <column> = <literal>}
     */
    private UpdateStmt.Assignment parseAssignment() {
        String colName = consumeIdentifier("column name");
        consume(EQ, "=");
        LiteralExpr value = parseLiteral();
        return new UpdateStmt.Assignment(colName, value);
    }

    // =========================================================================
    // DML — DELETE
    // =========================================================================

    /**
     * {@code DELETE FROM <table> [WHERE <cond>]}
     */
    private DeleteStmt parseDelete() {
        consume(DELETE, "DELETE");
        consume(FROM,   "FROM");
        String tableName = consumeIdentifier("table name");

        Optional<WhereClause> where = Optional.empty();
        if (check(WHERE)) {
            advance();
            where = Optional.of(parseWhereClause());
        }

        return new DeleteStmt(tableName, where);
    }

    // =========================================================================
    // WHERE clause
    // =========================================================================

    /**
     * Parses one or more predicates joined by AND.
     * <pre>
     *   predicate (AND predicate)*
     * </pre>
     */
    private WhereClause parseWhereClause() {
        List<BinaryExpr> predicates = new ArrayList<>();
        predicates.add(parsePredicate());

        while (check(AND)) {
            advance(); // consume AND
            predicates.add(parsePredicate());
        }

        return new WhereClause(predicates);
    }

    /**
     * Parses a single comparison predicate: {@code column op literal}.
     */
    private BinaryExpr parsePredicate() {
        String colName = consumeIdentifier("column name in predicate");
        Operator op    = parseOperator();
        LiteralExpr rhs = parseLiteral();
        return new BinaryExpr(new ColumnRefExpr(colName), op, rhs);
    }

    private Operator parseOperator() {
        Token token = advance();
        return switch (token.type()) {
            case EQ  -> Operator.EQ;
            case NEQ -> Operator.NEQ;
            case LT  -> Operator.LT;
            case GT  -> Operator.GT;
            case LTE -> Operator.LTE;
            case GTE -> Operator.GTE;
            default  -> throw new ParseException(
                    "Expected a comparison operator (=, !=, <, >, <=, >=) " +
                    "but found: '" + token.lexeme() + "'",
                    token.line());
        };
    }

    // =========================================================================
    // Literal parsing
    // =========================================================================

    /**
     * Parses any scalar literal value: int, long, double, string, boolean.
     */
    private LiteralExpr parseLiteral() {
        Token token = peek();

        return switch (token.type()) {
            case LITERAL_INT -> {
                advance();
                yield new LiteralExpr(Integer.parseInt(token.lexeme()));
            }
            case LITERAL_LONG -> {
                advance();
                yield new LiteralExpr(Long.parseLong(token.lexeme()));
            }
            case LITERAL_DOUBLE -> {
                advance();
                yield new LiteralExpr(Double.parseDouble(token.lexeme()));
            }
            case LITERAL_STRING -> {
                advance();
                yield new LiteralExpr(token.lexeme()); // already stripped of quotes
            }
            case TRUE -> {
                advance();
                yield new LiteralExpr(Boolean.TRUE);
            }
            case FALSE -> {
                advance();
                yield new LiteralExpr(Boolean.FALSE);
            }
            default -> throw new ParseException(
                    "Expected a literal value but found: '" + token.lexeme() + "'",
                    token.line());
        };
    }

    // =========================================================================
    // Token navigation helpers
    // =========================================================================

    /** Returns the current token without consuming it. */
    private Token peek() {
        return tokens.get(current);
    }

    /** Returns the current token and advances the cursor. */
    private Token advance() {
        if (!isAtEnd()) current++;
        return tokens.get(current - 1);
    }

    /** Returns true if the current token's type matches {@code type}. */
    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    /** Returns true if the current token is EOF. */
    private boolean isAtEnd() {
        return peek().type() == EOF;
    }

    /**
     * Asserts the current token has the expected type, advances, and returns it.
     * Throws a {@link ParseException} with a clear message otherwise.
     */
    private Token consume(TokenType expected, String description) {
        if (!check(expected)) {
            Token found = peek();
            throw new ParseException(
                    "Expected '%s' but found '%s'".formatted(description, found.lexeme()),
                    found.line());
        }
        return advance();
    }

    /**
     * Consumes the current token as an identifier.
     * Throws if the current token is not an {@code IDENTIFIER}.
     */
    private String consumeIdentifier(String context) {
        if (!check(IDENTIFIER)) {
            Token found = peek();
            throw new ParseException(
                    "Expected %s (identifier) but found: '%s'".formatted(context, found.lexeme()),
                    found.line());
        }
        return advance().lexeme();
    }
}
