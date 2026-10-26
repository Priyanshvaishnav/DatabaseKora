package dev.koradb.planner.optimizer;

import dev.koradb.catalog.InMemoryCatalog;
import dev.koradb.catalog.Schema;
import dev.koradb.catalog.Table;
import dev.koradb.planner.logical.*;
import dev.koradb.sql.ast.*;
import dev.koradb.sql.lexer.Lexer;
import dev.koradb.sql.parser.Parser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RuleBasedOptimizer")
class RuleBasedOptimizerTest {

    private InMemoryCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new InMemoryCatalog();
        catalog.createDatabase("testdb");
        catalog.useDatabase("testdb");

        Table users = new Table("users", new Schema(List.of(
                new ColumnDef("id", DataType.INT),
                new ColumnDef("name", DataType.VARCHAR),
                new ColumnDef("age", DataType.INT),
                new ColumnDef("city", DataType.VARCHAR)
        )));
        catalog.createTable(users);

        Table orders = new Table("orders", new Schema(List.of(
                new ColumnDef("id", DataType.INT),
                new ColumnDef("user_id", DataType.INT),
                new ColumnDef("amount", DataType.DOUBLE),
                new ColumnDef("status", DataType.VARCHAR)
        )));
        catalog.createTable(orders);
    }

    private SelectStmt parse(String sql) {
        return (SelectStmt) new Parser(new Lexer(sql).tokenize()).parse();
    }

    @Test
    @DisplayName("ConstantFoldingRule eliminates tautologies")
    void constantFoldingTautology() {
        SelectStmt stmt = parse("SELECT * FROM users WHERE 1 = 1");
        LogicalPlanNode initial = LogicalPlanBuilder.build(stmt, catalog);

        assertThat(initial).isInstanceOf(LogicalFilter.class);

        ConstantFoldingRule rule = new ConstantFoldingRule();
        LogicalPlanNode optimized = rule.apply(initial, catalog);

        // Entire tautology filter eliminated, leaving just the scan
        assertThat(optimized).isInstanceOf(LogicalScan.class);
    }

    @Test
    @DisplayName("ConstantFoldingRule normalizes literal = col to col = literal")
    void constantFoldingNormalizesOperands() {
        SelectStmt stmt = parse("SELECT * FROM users WHERE 42 = id");
        LogicalPlanNode initial = LogicalPlanBuilder.build(stmt, catalog);

        ConstantFoldingRule rule = new ConstantFoldingRule();
        LogicalPlanNode optimized = rule.apply(initial, catalog);

        assertThat(optimized).isInstanceOf(LogicalFilter.class);
        LogicalFilter filter = (LogicalFilter) optimized;
        BinaryExpr pred = (BinaryExpr) filter.predicates().get(0);
        assertThat(pred.left()).isInstanceOf(ColumnRefExpr.class);
        assertThat(((ColumnRefExpr) pred.left()).columnName()).isEqualTo("id");
        assertThat(pred.right()).isInstanceOf(LiteralExpr.class);
        assertThat(((LiteralExpr) pred.right()).value()).isEqualTo(42);
    }

    @Test
    @DisplayName("PredicatePushdownRule pushes filters down past join to respective scan leaves")
    void pushdownPastJoin() {
        SelectStmt stmt = parse("SELECT users.name, orders.amount FROM users JOIN orders ON users.id = orders.user_id WHERE users.age > 21 AND orders.amount > 100.0");
        LogicalPlanNode initial = LogicalPlanBuilder.build(stmt, catalog);

        RuleBasedOptimizer rbo = new RuleBasedOptimizer();
        LogicalPlanNode optimized = rbo.optimize(initial, catalog);

        // Top should be project
        assertThat(optimized).isInstanceOf(LogicalProject.class);
        LogicalPlanNode child = ((LogicalProject) optimized).child();

        // Under project should be the join
        assertThat(child).isInstanceOf(LogicalJoin.class);
        LogicalJoin join = (LogicalJoin) child;

        // Left scan (users) should have users.age > 21 pushed down
        assertThat(join.left()).isInstanceOf(LogicalScan.class);
        LogicalScan leftScan = (LogicalScan) join.left();
        assertThat(leftScan.pushedPredicates()).hasSize(1);
        assertThat(((BinaryExpr) leftScan.pushedPredicates().get(0)).operator()).isEqualTo(Operator.GT);

        // Right scan (orders) should have orders.amount > 100.0 pushed down
        assertThat(join.right()).isInstanceOf(LogicalScan.class);
        LogicalScan rightScan = (LogicalScan) join.right();
        assertThat(rightScan.pushedPredicates()).hasSize(1);
        assertThat(((BinaryExpr) rightScan.pushedPredicates().get(0)).operator()).isEqualTo(Operator.GT);
    }

    @Test
    @DisplayName("ProjectionPruningRule restricts scanned columns to only required ones")
    void projectionPruning() {
        SelectStmt stmt = parse("SELECT name FROM users WHERE age > 30");
        LogicalPlanNode initial = LogicalPlanBuilder.build(stmt, catalog);

        ProjectionPruningRule pruning = new ProjectionPruningRule();
        LogicalPlanNode optimized = pruning.apply(initial, catalog);

        // Traverse down to LogicalScan
        LogicalPlanNode curr = optimized;
        while (!curr.getChildren().isEmpty()) {
            curr = curr.getChildren().get(0);
        }

        assertThat(curr).isInstanceOf(LogicalScan.class);
        LogicalScan scan = (LogicalScan) curr;
        // Should only project 'name' and 'age' (needed for filter), pruning 'id' and 'city'
        assertThat(scan.projectedColumns()).containsExactlyInAnyOrder("name", "age");
    }
}
