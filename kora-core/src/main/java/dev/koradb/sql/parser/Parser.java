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
 *   <li>{@link #parse()} parses exactly one statement (backward-compatible).</li>
 *   <li>{@link #parseAll()} parses a multi-statement script separated by semicolons,
 *       returning all statements as a list. This enables SQL editors (e.g. DBeaver)
 *       to send a batch of statements in a single request.</li>
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

    /**
     * Parses all SQL statements from the token stream, separated by semicolons.
     * Enables multi-statement scripts (e.g. from DBeaver or CLI scripts).
     *
     * <pre>
     *   CREATE TABLE users (id INT, name VARCHAR);
     *   INSERT INTO users VALUES (1, 'Alice');
     *   SELECT * FROM users;
     * </pre>
     *
     * @return ordered list of all parsed {@link Statement} AST nodes (never empty)
     * @throws ParseException if any statement in the script cannot be parsed
     */
    public List<Statement> parseAll() {
        log.debug("Parsing multi-statement script ({} token(s))", tokens.size());
        List<Statement> statements = new ArrayList<>();

        // Skip any leading semicolons / blank lines
        while (check(SEMICOLON)) advance();

        while (!isAtEnd()) {
            statements.add(statement());
            // Consume semicolons between statements
            while (check(SEMICOLON)) advance();
        }

        if (statements.isEmpty()) {
            throw new ParseException("No statements found in input", 0);
        }
        return statements;
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
            case EXPLAIN  -> parseExplain();
            case BEGIN    -> parseBegin();
            case COMMIT   -> parseCommit();
            case ROLLBACK -> parseRollback();
            case DESC     -> parseDescribe();
            case IDENTIFIER -> {
                if (token.lexeme().equalsIgnoreCase("SHOW")) yield parseShow();
                if (token.lexeme().equalsIgnoreCase("DESCRIBE")) yield parseDescribe();
                if (token.lexeme().equalsIgnoreCase("TRUNCATE")) yield parseTruncate();
                if (token.lexeme().equalsIgnoreCase("ALTER")) yield parseAlter();
                throw new ParseException("Expected a SQL statement but found: '" + token.lexeme() + "'", token.line());
            }
            default       -> throw new ParseException(
                    "Expected a SQL statement but found: '" + token.lexeme() + "'",
                    token.line());
        };
    }

    // =========================================================================
    // DDL — CREATE
    // =========================================================================

    /**
     * Dispatches CREATE DATABASE | CREATE TABLE | CREATE INDEX | CREATE UNIQUE INDEX.
     */
    private Statement parseCreate() {
        consume(CREATE, "CREATE");

        Token next = peek();
        return switch (next.type()) {
            case DATABASE -> parseCreateDatabase();
            case TABLE    -> parseCreateTable();
            case INDEX    -> parseCreateIndex(false);
            case UNIQUE   -> {
                advance(); // consume UNIQUE
                consume(INDEX, "INDEX");
                yield parseCreateIndex(true);
            }
            default       -> throw new ParseException(
                    "Expected DATABASE, TABLE, or INDEX after CREATE, found: '" + next.lexeme() + "'",
                    next.line());
        };
    }

    /**
     * {@code CREATE [UNIQUE] INDEX <name> ON <tableName> ( <colName> )}
     */
    private CreateIndexStmt parseCreateIndex(boolean unique) {
        if (!unique) {
            consume(INDEX, "INDEX");
        }
        boolean ifNotExists = parseIfNotExists();
        String indexName = consumeIdentifier("index name");
        consume(ON, "ON");
        String tableName = consumeIdentifier("table name");
        consume(LPAREN, "(");
        String columnName = consumeIdentifier("column name");
        if (check(COMMA)) {
            throw new ParseException("Indexes on more than one column are not supported", peek().line());
        }
        consume(RPAREN, ")");
        return new CreateIndexStmt(indexName, tableName, columnName, unique, ifNotExists);
    }

    /**
     * {@code CREATE DATABASE <name>}
     */
    private CreateDatabaseStmt parseCreateDatabase() {
        consume(DATABASE, "DATABASE");
        boolean ifNotExists = parseIfNotExists();
        String name = consumeIdentifier("database name");
        return new CreateDatabaseStmt(name, ifNotExists);
    }

    /**
     * {@code CREATE TABLE <name> ( <col> <type> [, ...] )}
     */
    private CreateTableStmt parseCreateTable() {
        consume(TABLE, "TABLE");
        boolean ifNotExists = parseIfNotExists();
        String tableName = consumeIdentifier("table name");

        consume(LPAREN, "(");
        List<ColumnDef> columns = new ArrayList<>();
        String[] primaryKey = {null};
        List<String> unique = new ArrayList<>();

        do {
            if (!columns.isEmpty() || primaryKey[0] != null || !unique.isEmpty()) {
                advance(); // ','
                if (check(RPAREN)) {
                    throw new ParseException(
                            "Trailing comma in column list — remove the comma before ')'",
                            peek().line());
                }
            }
            if (isWord("PRIMARY")) {                     // table constraint PRIMARY KEY (col)
                advance();
                expectWord("KEY");
                setPrimaryKey(primaryKey, parseSingleColumnList("PRIMARY KEY"));
            } else if (check(UNIQUE)) {                  // table constraint UNIQUE (col)
                advance();
                unique.add(parseSingleColumnList("UNIQUE"));
            } else {
                columns.add(parseColumnDef(primaryKey, unique));
            }
        } while (check(COMMA));

        consume(RPAREN, ")");
        if (columns.isEmpty()) {
            throw new ParseException("CREATE TABLE needs at least one column", peek().line());
        }
        // the PRIMARY KEY column is NOT NULL
        if (primaryKey[0] != null) {
            boolean found = false;
            for (int i = 0; i < columns.size(); i++) {
                ColumnDef c = columns.get(i);
                if (c.name().equalsIgnoreCase(primaryKey[0])) {
                    columns.set(i, new ColumnDef(c.name(), c.dataType(), true));
                    found = true;
                }
            }
            if (!found) throw new ParseException("PRIMARY KEY column '" + primaryKey[0] + "' is not a column of the table", peek().line());
        }
        for (String u : unique) {
            if (columns.stream().noneMatch(c -> c.name().equalsIgnoreCase(u))) {
                throw new ParseException("UNIQUE column '" + u + "' is not a column of the table", peek().line());
            }
        }
        return new CreateTableStmt(tableName, columns, primaryKey[0], unique, ifNotExists);
    }

    private void setPrimaryKey(String[] pk, String column) {
        if (pk[0] != null) throw new ParseException("A table can have only one PRIMARY KEY", peek().line());
        pk[0] = column;
    }

    /** {@code ( col )} — composite keys are not supported. */
    private String parseSingleColumnList(String what) {
        consume(LPAREN, "(");
        String col = consumeIdentifier("column name");
        if (check(COMMA)) {
            throw new ParseException(what + " on more than one column is not supported", peek().line());
        }
        consume(RPAREN, ")");
        return col;
    }

    /**
     * {@code <columnName> <dataType> [NOT NULL | NULL] [PRIMARY KEY] [UNIQUE]} (constraints in any order)
     */
    private ColumnDef parseColumnDef(String[] primaryKey, List<String> unique) {
        String   colName  = consumeIdentifier("column name");
        DataType dataType = parseDataType();
        boolean notNull = false;
        while (true) {
            if (check(NOT) && peekType(1) == NULL) {
                advance();
                advance();
                notNull = true;
            } else if (check(NULL)) {
                advance();
            } else if (isWord("PRIMARY")) {
                advance();
                expectWord("KEY");
                setPrimaryKey(primaryKey, colName);
                notNull = true;
            } else if (check(UNIQUE)) {
                advance();
                unique.add(colName);
            } else {
                break;
            }
        }
        return new ColumnDef(colName, dataType, notNull);
    }

    private ColumnDef parseColumnDef() {
        return parseColumnDef(new String[1], new ArrayList<>());
    }

    /** True when the current token is the (non-reserved) word {@code w}. */
    private boolean isWord(String w) {
        Token t = peek();
        return t.type() == IDENTIFIER && t.lexeme().equalsIgnoreCase(w);
    }

    private void expectWord(String w) {
        if (!isWord(w)) {
            throw new ParseException("Expected " + w + " but found: '" + peek().lexeme() + "'", peek().line());
        }
        advance();
    }

    /** Optional {@code IF NOT EXISTS}. */
    private boolean parseIfNotExists() {
        if (isWord("IF")) {
            advance();
            consume(NOT, "NOT");
            consume(EXISTS, "EXISTS");
            return true;
        }
        return false;
    }

    /** Optional {@code IF EXISTS}. */
    private boolean parseIfExists() {
        if (isWord("IF")) {
            advance();
            consume(EXISTS, "EXISTS");
            return true;
        }
        return false;
    }

    // TRUNCATE [TABLE] <name>
    private TruncateStmt parseTruncate() {
        advance(); // TRUNCATE
        if (check(TABLE)) advance();
        return new TruncateStmt(consumeIdentifier("table name"));
    }

    // ALTER TABLE <name> ADD [COLUMN] <col> <type> [NOT NULL]
    private AlterTableStmt parseAlter() {
        advance(); // ALTER
        consume(TABLE, "TABLE");
        String table = consumeIdentifier("table name");
        if (!isWord("ADD")) {
            throw new ParseException("Only ALTER TABLE ... ADD [COLUMN] is supported, found: '" + peek().lexeme() + "'", peek().line());
        }
        advance();
        if (isWord("COLUMN")) advance();
        String[] pk = new String[1];
        List<String> unique = new ArrayList<>();
        ColumnDef col = parseColumnDef(pk, unique);
        if (pk[0] != null || !unique.isEmpty()) {
            throw new ParseException("ALTER TABLE ADD COLUMN does not support PRIMARY KEY or UNIQUE; create a unique index instead", peek().line());
        }
        return new AlterTableStmt(table, col);
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
            case INDEX    -> parseDropIndex();
            default       -> throw new ParseException(
                    "Expected DATABASE, TABLE, or INDEX after DROP, found: '" + next.lexeme() + "'",
                    next.line());
        };
    }

    private DropDatabaseStmt parseDropDatabase() {
        consume(DATABASE, "DATABASE");
        boolean ifExists = parseIfExists();
        String name = consumeIdentifier("database name");
        return new DropDatabaseStmt(name, ifExists);
    }

    private DropTableStmt parseDropTable() {
        consume(TABLE, "TABLE");
        boolean ifExists = parseIfExists();
        String name = consumeIdentifier("table name");
        return new DropTableStmt(name, ifExists);
    }

    /**
     * {@code DROP INDEX <indexName> [ON <tableName>]}
     */
    private DropIndexStmt parseDropIndex() {
        consume(INDEX, "INDEX");
        boolean ifExists = parseIfExists();
        String indexName = consumeIdentifier("index name");
        String tableName = null;
        if (check(ON)) {
            advance();
            tableName = consumeIdentifier("table name");
        }
        return new DropIndexStmt(indexName, tableName, ifExists);
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
     * {@code INSERT INTO <table> [( c1, c2, ... )] VALUES ( v, ... ) [, ( v, ... )]...}
     */
    private InsertStmt parseInsert() {
        consume(INSERT, "INSERT");
        consume(INTO,   "INTO");
        String tableName = consumeIdentifier("table name");

        List<String> columns = null;
        if (check(LPAREN)) {
            advance();
            columns = new ArrayList<>();
            columns.add(consumeIdentifier("column name"));
            while (check(COMMA)) {
                advance();
                columns.add(consumeIdentifier("column name"));
            }
            consume(RPAREN, ")");
        }
        consume(VALUES, "VALUES");

        List<List<LiteralExpr>> rows = new ArrayList<>();
        do {
            if (!rows.isEmpty()) advance(); // ',' between rows
            consume(LPAREN, "(");
            List<LiteralExpr> values = new ArrayList<>();
            values.add(parseLiteral());
            while (check(COMMA)) {
                advance();
                values.add(parseLiteral());
            }
            consume(RPAREN, ")");
            rows.add(values);
        } while (check(COMMA));

        return new InsertStmt(tableName, columns, rows);
    }

    // =========================================================================
    // DML — SELECT
    // =========================================================================

    /**
     * {@code SELECT [DISTINCT] (*|item,...) [FROM <table> [alias] [JOIN ...]...] [WHERE <cond>]
     * [GROUP BY c, ...] [HAVING <cond>] [ORDER BY ...] [LIMIT ... [OFFSET ...]]}
     */
    private SelectStmt parseSelect() {
        consume(SELECT, "SELECT");
        boolean distinct = false;
        if (check(DISTINCT)) {
            advance();
            distinct = true;
        }

        List<SelectItem> selectItems = new ArrayList<>();
        if (check(STAR)) {
            advance(); // SELECT * -> selectItems remains empty
        } else {
            selectItems.add(parseSelectItem());
            while (check(COMMA)) {
                advance();
                selectItems.add(parseSelectItem());
            }
        }

        String tableName = null;
        String tableAlias = null;
        List<JoinClause> joins = new ArrayList<>();
        if (check(FROM)) {
            advance();
            tableName = consumeIdentifier("table name");
            tableAlias = parseOptionalAlias();

            // [INNER] JOIN | LEFT [OUTER] JOIN  <table> [alias] ON <condition>
            while (check(JOIN) || check(INNER) || check(LEFT)) {
                boolean left = false;
                if (check(INNER)) {
                    advance();
                } else if (check(LEFT)) {
                    advance();
                    if (check(OUTER)) advance();
                    left = true;
                }
                consume(JOIN, "JOIN");
                String joinTable = consumeIdentifier("joined table name");
                String joinAlias = parseOptionalAlias();
                consume(ON, "ON");
                joins.add(new JoinClause(joinTable, parseExpr(), joinAlias, left));
            }
        } else if (selectItems.isEmpty()) {
            throw new ParseException("SELECT * needs a FROM clause", peek().line());
        }

        Optional<WhereClause> where = Optional.empty();
        if (check(WHERE)) {
            advance();
            where = Optional.of(parseWhereClause());
        }

        // GROUP BY c1 [, c2 ...]
        List<String> groupBy = new ArrayList<>();
        if (check(GROUP)) {
            advance();
            consume(BY, "BY");
            groupBy.add(consumeQualifiedIdentifier("group by column"));
            while (check(COMMA)) {
                advance();
                groupBy.add(consumeQualifiedIdentifier("group by column"));
            }
        }

        Optional<Expression> having = Optional.empty();
        if (check(HAVING)) {
            advance();
            having = Optional.of(parseExpr());
        }

        // Optional ORDER BY: ORDER BY <col> [ASC | DESC] [, ...]
        List<OrderItem> orderBy = new ArrayList<>();
        if (check(ORDER)) {
            advance();
            consume(BY, "BY");
            orderBy.add(parseOrderItem());
            while (check(COMMA)) {
                advance();
                orderBy.add(parseOrderItem());
            }
        }

        // Optional LIMIT: LIMIT <count> [OFFSET <offset>]
        Optional<Integer> limit = Optional.empty();
        Optional<Integer> offset = Optional.empty();
        if (check(LIMIT)) {
            advance();
            limit = Optional.of(parseIntegerLiteral("LIMIT count"));
            if (check(OFFSET)) {
                advance();
                offset = Optional.of(parseIntegerLiteral("OFFSET count"));
            }
        } else if (check(OFFSET)) {
            advance();
            offset = Optional.of(parseIntegerLiteral("OFFSET count"));
        }

        return new SelectStmt(selectItems, tableName, tableAlias, joins, where, groupBy, having,
                orderBy, limit, offset, distinct);
    }

    /** {@code [AS] alias} after a table name (only a plain identifier can be a bare alias). */
    private String parseOptionalAlias() {
        if (check(AS)) {
            advance();
            return consumeIdentifier("alias");
        }
        if (peek().type() == IDENTIFIER) {
            return advance().lexeme();
        }
        return null;
    }

    private SelectItem parseSelectItem() {
        Token token = peek();
        AggregateType aggType = null;
        switch (token.type()) {
            case COUNT -> aggType = AggregateType.COUNT;
            case SUM   -> aggType = AggregateType.SUM;
            case AVG   -> aggType = AggregateType.AVG;
            case MIN   -> aggType = AggregateType.MIN;
            case MAX   -> aggType = AggregateType.MAX;
            default    -> {}
        }
        // "count" without "(" is an ordinary column name
        if (aggType != null && (current + 1 >= tokens.size() || tokens.get(current + 1).type() != LPAREN)) {
            aggType = null;
        }

        if (aggType != null) {
            advance(); // consume aggregate keyword
            consume(LPAREN, "(");
            String col;
            if (aggType == AggregateType.COUNT && check(STAR)) {
                advance(); // COUNT(*)
                col = "*";
            } else {
                col = consumeQualifiedIdentifier("column name inside " + aggType);
            }
            consume(RPAREN, ")");

            String alias = null;
            if (check(AS)) {
                advance();
                alias = consumeIdentifier("alias");
            } else if (peek().type() == IDENTIFIER) {
                alias = advance().lexeme();
            }

            return SelectItem.aggregate(aggType, col, alias);
        }

        int startToken = current;
        Expression expr = parseExpr();
        String text = textOf(startToken, current);
        String alias = null;
        if (check(AS)) {
            advance();
            alias = consumeIdentifier("alias");
        } else if (peek().type() == IDENTIFIER) {
            alias = advance().lexeme();
        }

        if (expr instanceof ColumnRefExpr ref) {
            return alias != null ? SelectItem.column(ref.columnName(), alias) : SelectItem.column(ref.columnName());
        }
        if (expr instanceof AggregateExpr) {
            throw new ParseException("Aggregates cannot be combined with other expressions in a select item: " + text, peek().line());
        }
        return SelectItem.computed(expr, text, alias);
    }

    /** SQL text of tokens [from, to) — the display name of a computed select item. */
    private String textOf(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            Token t = tokens.get(i);
            String lex = t.type() == LITERAL_STRING ? "'" + t.lexeme().replace("'", "''") + "'" : t.lexeme();
            boolean tight = t.type() == DOT || t.type() == RPAREN || t.type() == COMMA
                    || (i > from && (tokens.get(i - 1).type() == DOT || tokens.get(i - 1).type() == LPAREN));
            if (sb.length() > 0 && !tight) sb.append(' ');
            sb.append(lex);
        }
        return sb.toString();
    }

    private OrderItem parseOrderItem() {
        String colName;
        if (isAggregateToken(peek().type()) && tokens.get(current + 1).type() == LPAREN) {
            colName = ((AggregateExpr) parsePrimary()).key();
        } else {
            colName = consumeQualifiedIdentifier("ORDER BY column");
        }
        boolean ascending = true;
        if (check(ASC)) {
            advance();
        } else if (check(DESC)) {
            advance();
            ascending = false;
        }
        return new OrderItem(colName, ascending);
    }

    private int parseIntegerLiteral(String context) {
        Token token = peek();
        if (token.type() == LITERAL_INT) {
            advance();
            return Integer.parseInt(token.lexeme());
        }
        throw new ParseException(
                "Expected integer for %s but found: '%s'".formatted(context, token.lexeme()),
                token.line());
    }

    private String consumeQualifiedIdentifier(String context) {
        String name = consumeIdentifier(context);
        if (check(DOT)) {
            advance();
            String sub = consumeIdentifier("column name");
            return name + "." + sub;
        }
        return name;
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
        return new UpdateStmt.Assignment(colName, parseExpr());
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
    // EXPLAIN [ANALYZE]
    // =========================================================================

    /**
     * {@code EXPLAIN [ANALYZE] <statement>}
     */
    private ExplainStmt parseExplain() {
        consume(EXPLAIN, "EXPLAIN");
        boolean analyze = false;
        if (check(ANALYZE)) {
            advance();
            analyze = true;
        }
        Statement target = statement();
        return new ExplainStmt(target, analyze);
    }

    // =========================================================================
    // Transactions
    // =========================================================================

    private BeginStmt parseBegin() {
        consume(BEGIN, "BEGIN");
        if (check(TRANSACTION)) {
            advance();
        }
        return new BeginStmt();
    }

    private CommitStmt parseCommit() {
        consume(COMMIT, "COMMIT");
        if (check(TRANSACTION)) {
            advance();
        }
        return new CommitStmt();
    }

    // SHOW TABLES | SHOW DATABASES  ("TABLES"/"DATABASES" are not reserved words)
    private ShowStmt parseShow() {
        advance(); // SHOW
        Token what = peek();
        String w = what.lexeme() == null ? "" : what.lexeme().toUpperCase(java.util.Locale.ROOT);
        ShowStmt stmt = switch (w) {
            case "TABLES"    -> new ShowStmt(ShowStmt.Kind.TABLES, null);
            case "DATABASES" -> new ShowStmt(ShowStmt.Kind.DATABASES, null);
            default -> throw new ParseException("Expected TABLES or DATABASES after SHOW but found: '" + what.lexeme() + "'", what.line());
        };
        advance();
        return stmt;
    }

    // DESCRIBE <table> | DESC <table>
    private ShowStmt parseDescribe() {
        advance(); // DESCRIBE / DESC
        return new ShowStmt(ShowStmt.Kind.COLUMNS, consumeIdentifier("table name"));
    }

    private RollbackStmt parseRollback() {
        consume(ROLLBACK, "ROLLBACK");
        if (check(TRANSACTION)) {
            advance();
        }
        return new RollbackStmt();
    }

    // =========================================================================
    // WHERE clause
    // =========================================================================

    /**
     * Parses a boolean expression; its top-level AND chain becomes the list of conjuncts.
     */
    private WhereClause parseWhereClause() {
        List<Expression> conjuncts = new ArrayList<>();
        flattenAnd(parseExpr(), conjuncts);
        return new WhereClause(conjuncts);
    }

    private static void flattenAnd(Expression e, List<Expression> out) {
        if (e instanceof BinaryExpr b && b.operator() == Operator.AND) {
            flattenAnd(b.left(), out);
            flattenAnd(b.right(), out);
        } else {
            out.add(e);
        }
    }

    // expr := or ; or := and (OR and)* ; and := not (AND not)* ; not := NOT not | predicate
    private Expression parseExpr() {
        return parseOr();
    }

    private Expression parseOr() {
        Expression e = parseAnd();
        while (check(OR)) {
            advance();
            e = new BinaryExpr(e, Operator.OR, parseAnd());
        }
        return e;
    }

    private Expression parseAnd() {
        Expression e = parseNot();
        while (check(AND)) {
            advance();
            e = new BinaryExpr(e, Operator.AND, parseNot());
        }
        return e;
    }

    private Expression parseNot() {
        if (check(NOT)) {
            advance();
            return new UnaryExpr(UnaryExpr.Kind.NOT, parseNot());
        }
        return parsePredicate();
    }

    /**
     * predicate := additive [ cmpOp additive | IS [NOT] NULL | [NOT] IN (...) | [NOT] BETWEEN a AND b
     *                        | [NOT] LIKE pattern ]
     */
    private Expression parsePredicate() {
        Expression left = parseAdditive();
        if (check(IS)) {
            advance();
            boolean not = false;
            if (check(NOT)) {
                advance();
                not = true;
            }
            consume(NULL, "NULL");
            return new UnaryExpr(not ? UnaryExpr.Kind.IS_NOT_NULL : UnaryExpr.Kind.IS_NULL, left);
        }
        boolean negated = false;
        if (check(NOT) && (peekType(1) == IN || peekType(1) == BETWEEN || peekType(1) == LIKE)) {
            advance();
            negated = true;
        }
        if (check(IN)) {
            advance();
            consume(LPAREN, "(");
            if (check(SELECT)) {
                SelectStmt sub = parseSelect();
                consume(RPAREN, ")");
                return new InExpr(left, null, sub, negated);
            }
            List<Expression> list = new ArrayList<>();
            list.add(parseExpr());
            while (check(COMMA)) {
                advance();
                list.add(parseExpr());
            }
            consume(RPAREN, ")");
            return new InExpr(left, list, null, negated);
        }
        if (check(BETWEEN)) {
            advance();
            Expression low = parseAdditive();
            consume(AND, "AND");
            Expression high = parseAdditive();
            return new BetweenExpr(left, low, high, negated);
        }
        if (check(LIKE)) {
            advance();
            return new BinaryExpr(left, negated ? Operator.NOT_LIKE : Operator.LIKE, parseAdditive());
        }
        if (negated) {
            throw new ParseException("Expected IN, BETWEEN or LIKE after NOT", peek().line());
        }
        Operator op = switch (peek().type()) {
            case EQ  -> Operator.EQ;
            case NEQ -> Operator.NEQ;
            case LT  -> Operator.LT;
            case GT  -> Operator.GT;
            case LTE -> Operator.LTE;
            case GTE -> Operator.GTE;
            default  -> null;
        };
        if (op == null) {
            return left;
        }
        advance();
        return new BinaryExpr(left, op, parseAdditive());
    }

    private Expression parseAdditive() {
        Expression e = parseMultiplicative();
        while (check(PLUS) || check(MINUS)) {
            Operator op = advance().type() == PLUS ? Operator.ADD : Operator.SUB;
            e = new BinaryExpr(e, op, parseMultiplicative());
        }
        return e;
    }

    private Expression parseMultiplicative() {
        Expression e = parseUnary();
        while (check(STAR) || check(SLASH) || check(PERCENT)) {
            TokenType t = advance().type();
            Operator op = t == STAR ? Operator.MUL : t == SLASH ? Operator.DIV : Operator.MOD;
            e = new BinaryExpr(e, op, parseUnary());
        }
        return e;
    }

    private Expression parseUnary() {
        if (check(MINUS)) {
            TokenType next = peekType(1);
            if (next == LITERAL_INT || next == LITERAL_LONG || next == LITERAL_DOUBLE) {
                return parseLiteral();           // negative literal: -5, -2.5
            }
            advance();
            return new UnaryExpr(UnaryExpr.Kind.NEGATE, parseUnary());
        }
        if (check(PLUS)) {
            advance();
            return parseUnary();
        }
        return parsePrimary();
    }

    /** literal | column | aggregate(...) | ( expr ) | ( SELECT ... ) | EXISTS ( SELECT ... ) */
    private Expression parsePrimary() {
        Token t = peek();
        if (t.type() == LPAREN) {
            advance();
            if (check(SELECT)) {
                SelectStmt sub = parseSelect();
                consume(RPAREN, ")");
                return new SubqueryExpr(sub, false);
            }
            Expression e = parseExpr();
            consume(RPAREN, ")");
            return e;
        }
        if (t.type() == EXISTS) {
            advance();
            consume(LPAREN, "(");
            SelectStmt sub = parseSelect();
            consume(RPAREN, ")");
            return new SubqueryExpr(sub, true);
        }
        if (isAggregateToken(t.type()) && peekType(1) == LPAREN) {
            advance();
            consume(LPAREN, "(");
            String col;
            if (t.type() == COUNT && check(STAR)) {
                advance();
                col = "*";
            } else {
                col = consumeQualifiedIdentifier("column name inside " + t.lexeme().toUpperCase(java.util.Locale.ROOT));
            }
            consume(RPAREN, ")");
            return new AggregateExpr(AggregateType.valueOf(t.type().name()), col);
        }
        switch (t.type()) {
            case LITERAL_INT, LITERAL_LONG, LITERAL_DOUBLE, LITERAL_STRING, TRUE, FALSE, NULL -> {
                return parseLiteral();
            }
            default -> { }
        }
        if (t.type() == IDENTIFIER || isKeywordAsIdentifier(t.type())) {
            return new ColumnRefExpr(consumeQualifiedIdentifier("column name"));
        }
        throw new ParseException("Expected a value, column or expression but found: '" + t.lexeme() + "'", t.line());
    }

    private static boolean isAggregateToken(TokenType type) {
        return type == COUNT || type == SUM || type == AVG || type == MIN || type == MAX;
    }

    private TokenType peekType(int ahead) {
        int i = Math.min(current + ahead, tokens.size() - 1);
        return tokens.get(i).type();
    }

    // =========================================================================
    // Literal parsing
    // =========================================================================

    /**
     * Parses any scalar literal value: int, long, double, string, boolean.
     */
    private LiteralExpr parseLiteral() {
        Token token = peek();
        if (token.type() == MINUS) {
            // Negative numeric literal: -5, -2.5, -1E3
            advance();
            Token num = peek();
            return switch (num.type()) {
                case LITERAL_INT -> {
                    advance();
                    yield new LiteralExpr(-Integer.parseInt(num.lexeme()));
                }
                case LITERAL_LONG -> {
                    advance();
                    long v = -Long.parseLong(num.lexeme());
                    yield (v >= Integer.MIN_VALUE) ? new LiteralExpr((int) v) : new LiteralExpr(v);
                }
                case LITERAL_DOUBLE -> {
                    advance();
                    yield new LiteralExpr(-Double.parseDouble(num.lexeme()));
                }
                default -> throw new ParseException(
                        "Expected a number after '-' but found: '" + num.lexeme() + "'", num.line());
            };
        }
        if (token.type() == NULL) {
            advance();
            return new LiteralExpr(null);
        }

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
     * Consumes the current token as an identifier (or keyword used as identifier/alias).
     */
    private String consumeIdentifier(String context) {
        Token found = peek();
        if (found.type() == IDENTIFIER || isKeywordAsIdentifier(found.type())) {
            return advance().lexeme();
        }
        throw new ParseException(
                "Expected %s (identifier) but found: '%s'".formatted(context, found.lexeme()),
                found.line());
    }

    private boolean isKeywordAsIdentifier(TokenType type) {
        return switch (type) {
            case COUNT, SUM, AVG, MIN, MAX, ORDER, BY, ASC, DESC, LIMIT, OFFSET,
                 INDEX, TABLE, DATABASE, USE, UNIQUE, ON, SET, VALUES, GROUP,
                 EXPLAIN, ANALYZE, TRANSACTION -> true;
            default -> false;
        };
    }
}
