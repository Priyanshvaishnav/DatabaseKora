package dev.koradb.sql.parser;

import dev.koradb.exception.ParseException;
import dev.koradb.sql.ast.*;
import dev.koradb.sql.lexer.Lexer;
import dev.koradb.sql.lexer.Token;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the {@link Parser}.
 *
 * <p>Each test parses SQL text end-to-end through the Lexer and then the Parser,
 * then asserts the exact shape of the produced AST. This verifies that the
 * Lexer→Parser pipeline works correctly, not just each component in isolation.
 */
@DisplayName("Parser")
class ParserTest {

    // Helper: lex + parse
    private Statement parse(String sql) {
        List<Token> tokens = new Lexer(sql).tokenize();
        return new Parser(tokens).parse();
    }

    // =========================================================================
    // DDL — CREATE DATABASE
    // =========================================================================

    @Nested
    @DisplayName("CREATE DATABASE")
    class CreateDatabase {

        @Test
        @DisplayName("parses CREATE DATABASE")
        void basic() {
            Statement stmt = parse("CREATE DATABASE finance");
            assertThat(stmt).isInstanceOf(CreateDatabaseStmt.class);
            CreateDatabaseStmt s = (CreateDatabaseStmt) stmt;
            assertThat(s.databaseName()).isEqualTo("finance");
        }

        @Test
        @DisplayName("parses with trailing semicolon")
        void withSemicolon() {
            Statement stmt = parse("CREATE DATABASE finance;");
            assertThat(((CreateDatabaseStmt) stmt).databaseName()).isEqualTo("finance");
        }

        @Test
        @DisplayName("case-insensitive keywords")
        void caseInsensitive() {
            Statement stmt = parse("create database Finance");
            assertThat(((CreateDatabaseStmt) stmt).databaseName()).isEqualTo("Finance");
        }
    }

    // =========================================================================
    // DDL — DROP DATABASE
    // =========================================================================

    @Nested
    @DisplayName("DROP DATABASE")
    class DropDatabase {

        @Test
        @DisplayName("parses DROP DATABASE")
        void basic() {
            Statement stmt = parse("DROP DATABASE finance");
            assertThat(stmt).isInstanceOf(DropDatabaseStmt.class);
            assertThat(((DropDatabaseStmt) stmt).databaseName()).isEqualTo("finance");
        }
    }

    // =========================================================================
    // DDL — USE DATABASE
    // =========================================================================

    @Nested
    @DisplayName("USE database")
    class UseDatabase {

        @Test
        @DisplayName("parses USE statement")
        void basic() {
            Statement stmt = parse("USE finance");
            assertThat(stmt).isInstanceOf(UseDatabaseStmt.class);
            assertThat(((UseDatabaseStmt) stmt).databaseName()).isEqualTo("finance");
        }
    }

    // =========================================================================
    // DDL — CREATE TABLE
    // =========================================================================

    @Nested
    @DisplayName("CREATE TABLE")
    class CreateTable {

        @Test
        @DisplayName("single column")
        void singleColumn() {
            Statement stmt = parse("CREATE TABLE logs (id INT)");
            assertThat(stmt).isInstanceOf(CreateTableStmt.class);
            CreateTableStmt s = (CreateTableStmt) stmt;
            assertThat(s.tableName()).isEqualTo("logs");
            assertThat(s.columnDefs()).hasSize(1);
            assertThat(s.columnDefs().get(0).name()).isEqualTo("id");
            assertThat(s.columnDefs().get(0).dataType()).isEqualTo(DataType.INT);
        }

        @Test
        @DisplayName("all supported data types")
        void allDataTypes() {
            Statement stmt = parse("""
                    CREATE TABLE everything (
                        a INT,
                        b BIGINT,
                        c BOOLEAN,
                        d VARCHAR,
                        e DOUBLE,
                        f TIMESTAMP
                    )""");
            CreateTableStmt s = (CreateTableStmt) stmt;
            assertThat(s.columnDefs()).hasSize(6);
            assertThat(s.columnDefs().get(0).dataType()).isEqualTo(DataType.INT);
            assertThat(s.columnDefs().get(1).dataType()).isEqualTo(DataType.BIGINT);
            assertThat(s.columnDefs().get(2).dataType()).isEqualTo(DataType.BOOLEAN);
            assertThat(s.columnDefs().get(3).dataType()).isEqualTo(DataType.VARCHAR);
            assertThat(s.columnDefs().get(4).dataType()).isEqualTo(DataType.DOUBLE);
            assertThat(s.columnDefs().get(5).dataType()).isEqualTo(DataType.TIMESTAMP);
        }

        @Test
        @DisplayName("three-column users table")
        void usersTable() {
            Statement stmt = parse("CREATE TABLE users (id INT, name VARCHAR, age INT)");
            CreateTableStmt s = (CreateTableStmt) stmt;
            assertThat(s.tableName()).isEqualTo("users");
            assertThat(s.columnDefs()).extracting(ColumnDef::name)
                    .containsExactly("id", "name", "age");
        }

        @Test
        @DisplayName("trailing comma throws ParseException")
        void trailingComma() {
            assertThatThrownBy(() -> parse("CREATE TABLE t (id INT,)"))
                    .isInstanceOf(ParseException.class)
                    .hasMessageContaining("Trailing comma");
        }

        @Test
        @DisplayName("missing closing paren throws ParseException")
        void missingParen() {
            assertThatThrownBy(() -> parse("CREATE TABLE t (id INT"))
                    .isInstanceOf(ParseException.class);
        }

        @Test
        @DisplayName("unknown type throws ParseException")
        void unknownType() {
            assertThatThrownBy(() -> parse("CREATE TABLE t (id TEXT)"))
                    .isInstanceOf(ParseException.class)
                    .hasMessageContaining("data type");
        }
    }

    // =========================================================================
    // DDL — DROP TABLE
    // =========================================================================

    @Nested
    @DisplayName("DROP TABLE")
    class DropTable {

        @Test
        @DisplayName("parses DROP TABLE")
        void basic() {
            Statement stmt = parse("DROP TABLE users");
            assertThat(stmt).isInstanceOf(DropTableStmt.class);
            assertThat(((DropTableStmt) stmt).tableName()).isEqualTo("users");
        }
    }

    // =========================================================================
    // DML — INSERT
    // =========================================================================

    @Nested
    @DisplayName("INSERT")
    class Insert {

        @Test
        @DisplayName("integer values")
        void intValues() {
            Statement stmt = parse("INSERT INTO users VALUES (1, 30)");
            assertThat(stmt).isInstanceOf(InsertStmt.class);
            InsertStmt s = (InsertStmt) stmt;
            assertThat(s.tableName()).isEqualTo("users");
            assertThat(s.values()).hasSize(2);
            assertThat(s.values().get(0).value()).isEqualTo(1);
            assertThat(s.values().get(1).value()).isEqualTo(30);
        }

        @Test
        @DisplayName("mixed types: int, string, boolean, double")
        void mixedTypes() {
            Statement stmt = parse("INSERT INTO users VALUES (1, 'Alice', 30, 95000.0, true)");
            InsertStmt s = (InsertStmt) stmt;
            assertThat(s.values()).hasSize(5);
            assertThat(s.values().get(0).value()).isEqualTo(1);
            assertThat(s.values().get(1).value()).isEqualTo("Alice");
            assertThat(s.values().get(2).value()).isEqualTo(30);
            assertThat(s.values().get(3).value()).isEqualTo(95000.0);
            assertThat(s.values().get(4).value()).isEqualTo(Boolean.TRUE);
        }

        @Test
        @DisplayName("timestamp as string literal")
        void timestampString() {
            Statement stmt = parse("INSERT INTO events VALUES (1, '2024-01-15 10:00:00')");
            InsertStmt s = (InsertStmt) stmt;
            assertThat(s.values().get(1).value()).isEqualTo("2024-01-15 10:00:00");
        }

        @Test
        @DisplayName("single value")
        void singleValue() {
            Statement stmt = parse("INSERT INTO counts VALUES (42)");
            InsertStmt s = (InsertStmt) stmt;
            assertThat(s.values()).hasSize(1);
        }
    }

    // =========================================================================
    // DML — SELECT
    // =========================================================================

    @Nested
    @DisplayName("SELECT")
    class Select {

        @Test
        @DisplayName("SELECT * FROM table")
        void selectStar() {
            Statement stmt = parse("SELECT * FROM users");
            assertThat(stmt).isInstanceOf(SelectStmt.class);
            SelectStmt s = (SelectStmt) stmt;
            assertThat(s.isSelectStar()).isTrue();
            assertThat(s.tableName()).isEqualTo("users");
            assertThat(s.where()).isEmpty();
        }

        @Test
        @DisplayName("SELECT specific columns")
        void specificColumns() {
            Statement stmt = parse("SELECT name, age FROM users");
            SelectStmt s = (SelectStmt) stmt;
            assertThat(s.columns()).containsExactly("name", "age");
            assertThat(s.isSelectStar()).isFalse();
        }

        @Test
        @DisplayName("SELECT with single WHERE predicate")
        void withWhere() {
            Statement stmt = parse("SELECT * FROM users WHERE age > 20");
            SelectStmt s = (SelectStmt) stmt;
            assertThat(s.where()).isPresent();
            WhereClause where = s.where().get();
            assertThat(where.predicates()).hasSize(1);
            BinaryExpr pred = where.predicates().get(0);
            assertThat(((ColumnRefExpr) pred.left()).columnName()).isEqualTo("age");
            assertThat(pred.operator()).isEqualTo(Operator.GT);
            assertThat(((LiteralExpr) pred.right()).value()).isEqualTo(20);
        }

        @Test
        @DisplayName("SELECT with AND WHERE clause")
        void withAndWhere() {
            Statement stmt = parse("SELECT name FROM users WHERE age > 20 AND active = true");
            SelectStmt s = (SelectStmt) stmt;
            WhereClause where = s.where().get();
            assertThat(where.predicates()).hasSize(2);
            // first predicate: age > 20
            assertThat(((ColumnRefExpr) where.predicates().get(0).left()).columnName())
                    .isEqualTo("age");
            // second predicate: active = true
            assertThat(((ColumnRefExpr) where.predicates().get(1).left()).columnName())
                    .isEqualTo("active");
            assertThat(((LiteralExpr) where.predicates().get(1).right()).value())
                    .isEqualTo(Boolean.TRUE);
        }

        @Test
        @DisplayName("all comparison operators in WHERE")
        void allOperators() {
            assertThat(parseWhereOperator("=")).isEqualTo(Operator.EQ);
            assertThat(parseWhereOperator("!=")).isEqualTo(Operator.NEQ);
            assertThat(parseWhereOperator("<")).isEqualTo(Operator.LT);
            assertThat(parseWhereOperator(">")).isEqualTo(Operator.GT);
            assertThat(parseWhereOperator("<=")).isEqualTo(Operator.LTE);
            assertThat(parseWhereOperator(">=")).isEqualTo(Operator.GTE);
        }

        private Operator parseWhereOperator(String op) {
            SelectStmt s = (SelectStmt) parse("SELECT * FROM t WHERE id " + op + " 1");
            return s.where().get().predicates().get(0).operator();
        }

        @Test
        @DisplayName("SELECT with ORDER BY ASC and DESC")
        void withOrderBy() {
            Statement stmt = parse("SELECT name, age FROM users ORDER BY age DESC, name ASC");
            SelectStmt s = (SelectStmt) stmt;
            assertThat(s.orderBy()).hasSize(2);
            assertThat(s.orderBy().get(0).columnName()).isEqualTo("age");
            assertThat(s.orderBy().get(0).ascending()).isFalse();
            assertThat(s.orderBy().get(1).columnName()).isEqualTo("name");
            assertThat(s.orderBy().get(1).ascending()).isTrue();
        }

        @Test
        @DisplayName("SELECT with LIMIT and OFFSET")
        void withLimitAndOffset() {
            Statement stmt = parse("SELECT * FROM users LIMIT 10 OFFSET 5");
            SelectStmt s = (SelectStmt) stmt;
            assertThat(s.limit()).contains(10);
            assertThat(s.offset()).contains(5);
        }

        @Test
        @DisplayName("SELECT with JOIN ON condition")
        void withJoin() {
            Statement stmt = parse("SELECT u.name, o.total FROM users JOIN orders ON users.id = orders.user_id");
            SelectStmt s = (SelectStmt) stmt;
            assertThat(s.join()).isPresent();
            JoinClause j = s.join().get();
            assertThat(j.tableName()).isEqualTo("orders");
            assertThat(j.onCondition().operator()).isEqualTo(Operator.EQ);
            assertThat(((ColumnRefExpr) j.onCondition().left()).columnName()).isEqualTo("users.id");
            assertThat(((ColumnRefExpr) j.onCondition().right()).columnName()).isEqualTo("orders.user_id");
        }

        @Test
        @DisplayName("SELECT with GROUP BY and aggregate functions")
        void withGroupByAndAggregates() {
            Statement stmt = parse("SELECT dept, COUNT(*) AS emp_count, AVG(salary) FROM employees GROUP BY dept");
            SelectStmt s = (SelectStmt) stmt;
            assertThat(s.groupBy()).contains("dept");
            assertThat(s.selectItems()).hasSize(3);
            assertThat(s.selectItems().get(0).expression()).isEqualTo("dept");
            assertThat(s.selectItems().get(1).isAggregate()).isTrue();
            assertThat(s.selectItems().get(1).aggregateType()).isEqualTo(AggregateType.COUNT);
            assertThat(s.selectItems().get(1).alias()).isEqualTo("emp_count");
            assertThat(s.selectItems().get(2).isAggregate()).isTrue();
            assertThat(s.selectItems().get(2).aggregateType()).isEqualTo(AggregateType.AVG);
        }
    }

    // =========================================================================
    // DML — UPDATE
    // =========================================================================

    @Nested
    @DisplayName("UPDATE")
    class Update {

        @Test
        @DisplayName("single assignment with WHERE")
        void singleAssignmentWithWhere() {
            Statement stmt = parse("UPDATE users SET age = 31 WHERE id = 1");
            assertThat(stmt).isInstanceOf(UpdateStmt.class);
            UpdateStmt s = (UpdateStmt) stmt;
            assertThat(s.tableName()).isEqualTo("users");
            assertThat(s.assignments()).hasSize(1);
            assertThat(s.assignments().get(0).columnName()).isEqualTo("age");
            assertThat(s.assignments().get(0).value().value()).isEqualTo(31);
            assertThat(s.where()).isPresent();
        }

        @Test
        @DisplayName("multiple assignments")
        void multipleAssignments() {
            Statement stmt = parse("UPDATE users SET name = 'Bob', age = 25 WHERE id = 1");
            UpdateStmt s = (UpdateStmt) stmt;
            assertThat(s.assignments()).hasSize(2);
            assertThat(s.assignments().get(0).columnName()).isEqualTo("name");
            assertThat(s.assignments().get(1).columnName()).isEqualTo("age");
        }

        @Test
        @DisplayName("UPDATE without WHERE (updates all rows)")
        void withoutWhere() {
            Statement stmt = parse("UPDATE users SET active = false");
            UpdateStmt s = (UpdateStmt) stmt;
            assertThat(s.where()).isEmpty();
        }
    }

    // =========================================================================
    // DML — DELETE
    // =========================================================================

    @Nested
    @DisplayName("DELETE")
    class Delete {

        @Test
        @DisplayName("DELETE with WHERE")
        void withWhere() {
            Statement stmt = parse("DELETE FROM users WHERE id = 1");
            assertThat(stmt).isInstanceOf(DeleteStmt.class);
            DeleteStmt s = (DeleteStmt) stmt;
            assertThat(s.tableName()).isEqualTo("users");
            assertThat(s.where()).isPresent();
        }

        @Test
        @DisplayName("DELETE without WHERE (deletes all rows)")
        void withoutWhere() {
            Statement stmt = parse("DELETE FROM users");
            assertThat(((DeleteStmt) stmt).where()).isEmpty();
        }
    }

    // =========================================================================
    // DDL — CREATE INDEX / DROP INDEX
    // =========================================================================

    @Nested
    @DisplayName("CREATE INDEX")
    class CreateIndex {

        @Test
        @DisplayName("parses CREATE INDEX")
        void basicCreateIndex() {
            Statement stmt = parse("CREATE INDEX idx_user_id ON users (id)");
            assertThat(stmt).isInstanceOf(CreateIndexStmt.class);
            CreateIndexStmt s = (CreateIndexStmt) stmt;
            assertThat(s.indexName()).isEqualTo("idx_user_id");
            assertThat(s.tableName()).isEqualTo("users");
            assertThat(s.columnName()).isEqualTo("id");
            assertThat(s.unique()).isFalse();
        }

        @Test
        @DisplayName("parses CREATE UNIQUE INDEX")
        void uniqueIndex() {
            Statement stmt = parse("CREATE UNIQUE INDEX idx_email ON users (email);");
            assertThat(stmt).isInstanceOf(CreateIndexStmt.class);
            CreateIndexStmt s = (CreateIndexStmt) stmt;
            assertThat(s.indexName()).isEqualTo("idx_email");
            assertThat(s.tableName()).isEqualTo("users");
            assertThat(s.columnName()).isEqualTo("email");
            assertThat(s.unique()).isTrue();
        }
    }

    @Nested
    @DisplayName("DROP INDEX")
    class DropIndex {

        @Test
        @DisplayName("parses DROP INDEX")
        void basicDropIndex() {
            Statement stmt = parse("DROP INDEX idx_user_id");
            assertThat(stmt).isInstanceOf(DropIndexStmt.class);
            DropIndexStmt s = (DropIndexStmt) stmt;
            assertThat(s.indexName()).isEqualTo("idx_user_id");
            assertThat(s.tableName()).isNull();
        }

        @Test
        @DisplayName("parses DROP INDEX ON table")
        void dropIndexOnTable() {
            Statement stmt = parse("DROP INDEX idx_user_id ON users;");
            assertThat(stmt).isInstanceOf(DropIndexStmt.class);
            DropIndexStmt s = (DropIndexStmt) stmt;
            assertThat(s.indexName()).isEqualTo("idx_user_id");
            assertThat(s.tableName()).isEqualTo("users");
        }
    }

    // =========================================================================
    // Error cases
    // =========================================================================

    @Nested
    @DisplayName("Error cases")
    class ErrorCases {

        @Test
        @DisplayName("empty input throws ParseException")
        void emptyInput() {
            assertThatThrownBy(() -> parse(""))
                    .isInstanceOf(ParseException.class);
        }

        @Test
        @DisplayName("unknown statement start throws ParseException")
        void unknownStatement() {
            assertThatThrownBy(() -> parse("FETCH FROM users"))
                    .isInstanceOf(ParseException.class)
                    .hasMessageContaining("FETCH");
        }

        @Test
        @DisplayName("extra tokens after statement throws ParseException")
        void extraTokens() {
            assertThatThrownBy(() -> parse("SELECT * FROM users EXTRA_TOKEN"))
                    .isInstanceOf(ParseException.class)
                    .hasMessageContaining("Unexpected token");
        }

        @Test
        @DisplayName("null token list throws IllegalArgumentException")
        void nullTokens() {
            assertThatThrownBy(() -> new Parser(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // EXPLAIN [ANALYZE]
    // =========================================================================

    @Nested
    @DisplayName("EXPLAIN")
    class Explain {

        @Test
        @DisplayName("parses EXPLAIN SELECT")
        void basicExplain() {
            Statement stmt = parse("EXPLAIN SELECT * FROM users");
            assertThat(stmt).isInstanceOf(ExplainStmt.class);
            ExplainStmt explain = (ExplainStmt) stmt;
            assertThat(explain.analyze()).isFalse();
            assertThat(explain.statement()).isInstanceOf(SelectStmt.class);
            SelectStmt sel = (SelectStmt) explain.statement();
            assertThat(sel.tableName()).isEqualTo("users");
        }

        @Test
        @DisplayName("parses EXPLAIN ANALYZE SELECT")
        void explainAnalyze() {
            Statement stmt = parse("EXPLAIN ANALYZE SELECT id, name FROM users WHERE id = 1");
            assertThat(stmt).isInstanceOf(ExplainStmt.class);
            ExplainStmt explain = (ExplainStmt) stmt;
            assertThat(explain.analyze()).isTrue();
            assertThat(explain.statement()).isInstanceOf(SelectStmt.class);
            SelectStmt sel = (SelectStmt) explain.statement();
            assertThat(sel.tableName()).isEqualTo("users");
            assertThat(sel.columns()).containsExactly("id", "name");
        }
    }

    // =========================================================================
    // TRANSACTION STATEMENTS
    // =========================================================================

    @Nested
    @DisplayName("TRANSACTION")
    class Transactions {

        @Test
        @DisplayName("parses BEGIN and BEGIN TRANSACTION")
        void parseBegin() {
            Statement s1 = parse("BEGIN");
            assertThat(s1).isInstanceOf(dev.koradb.sql.ast.BeginStmt.class);

            Statement s2 = parse("BEGIN TRANSACTION");
            assertThat(s2).isInstanceOf(dev.koradb.sql.ast.BeginStmt.class);
        }

        @Test
        @DisplayName("parses COMMIT and COMMIT TRANSACTION")
        void parseCommit() {
            Statement s1 = parse("COMMIT");
            assertThat(s1).isInstanceOf(dev.koradb.sql.ast.CommitStmt.class);

            Statement s2 = parse("COMMIT TRANSACTION");
            assertThat(s2).isInstanceOf(dev.koradb.sql.ast.CommitStmt.class);
        }

        @Test
        @DisplayName("parses ROLLBACK and ROLLBACK TRANSACTION")
        void parseRollback() {
            Statement s1 = parse("ROLLBACK");
            assertThat(s1).isInstanceOf(dev.koradb.sql.ast.RollbackStmt.class);

            Statement s2 = parse("ROLLBACK TRANSACTION");
            assertThat(s2).isInstanceOf(dev.koradb.sql.ast.RollbackStmt.class);
        }
    }
}
