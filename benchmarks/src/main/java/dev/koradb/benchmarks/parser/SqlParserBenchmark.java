package dev.koradb.benchmarks.parser;

import dev.koradb.sql.ast.Statement;
import dev.koradb.sql.lexer.Lexer;
import dev.koradb.sql.lexer.Token;
import dev.koradb.sql.parser.Parser;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH Microbenchmark measuring SQL Lexer tokenization and Parser AST generation throughput.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class SqlParserBenchmark {

    public static final String SIMPLE_SELECT =
            "SELECT id, name, balance FROM accounts WHERE id = 42";

    public static final String COMPLEX_SELECT =
            "SELECT users.id, users.name, SUM(orders.amount) FROM users " +
            "JOIN orders ON users.id = orders.user_id " +
            "WHERE users.id > 100 " +
            "GROUP BY users.id " +
            "ORDER BY users.name ASC LIMIT 50 OFFSET 10";

    public static final String INSERT_STMT =
            "INSERT INTO accounts VALUES (101, 'John Doe', 7500.50, true)";

    public static final String UPDATE_STMT =
            "UPDATE accounts SET balance = 7500.0 WHERE id = 42";

    public static final String CREATE_TABLE_STMT =
            "CREATE TABLE customers (id INT, name VARCHAR, balance DOUBLE, active BOOLEAN)";

    @Benchmark
    public List<Token> tokenizeSimpleSelect() {
        return new Lexer(SIMPLE_SELECT).tokenize();
    }

    @Benchmark
    public List<Token> tokenizeComplexSelect() {
        return new Lexer(COMPLEX_SELECT).tokenize();
    }

    @Benchmark
    public Statement parseSimpleSelect() {
        List<Token> tokens = new Lexer(SIMPLE_SELECT).tokenize();
        return new Parser(tokens).parse();
    }

    @Benchmark
    public Statement parseComplexSelect() {
        List<Token> tokens = new Lexer(COMPLEX_SELECT).tokenize();
        return new Parser(tokens).parse();
    }

    @Benchmark
    public Statement parseInsert() {
        List<Token> tokens = new Lexer(INSERT_STMT).tokenize();
        return new Parser(tokens).parse();
    }

    @Benchmark
    public Statement parseUpdate() {
        List<Token> tokens = new Lexer(UPDATE_STMT).tokenize();
        return new Parser(tokens).parse();
    }

    @Benchmark
    public Statement parseCreateTable() {
        List<Token> tokens = new Lexer(CREATE_TABLE_STMT).tokenize();
        return new Parser(tokens).parse();
    }
}
