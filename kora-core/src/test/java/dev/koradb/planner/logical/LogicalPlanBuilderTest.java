package dev.koradb.planner.logical;

import dev.koradb.catalog.InMemoryCatalog;
import dev.koradb.catalog.Schema;
import dev.koradb.catalog.Table;
import dev.koradb.sql.ast.*;
import dev.koradb.sql.lexer.Lexer;
import dev.koradb.sql.parser.Parser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LogicalPlanBuilder")
class LogicalPlanBuilderTest {

    private InMemoryCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new InMemoryCatalog();
        catalog.createDatabase("testdb");
        catalog.useDatabase("testdb");

        Table users = new Table("users", new Schema(List.of(
                new ColumnDef("id", DataType.INT),
                new ColumnDef("name", DataType.VARCHAR),
                new ColumnDef("age", DataType.INT)
        )));
        catalog.createTable(users);

        Table orders = new Table("orders", new Schema(List.of(
                new ColumnDef("id", DataType.INT),
                new ColumnDef("user_id", DataType.INT),
                new ColumnDef("amount", DataType.DOUBLE)
        )));
        catalog.createTable(orders);
    }

    private SelectStmt parse(String sql) {
        return (SelectStmt) new Parser(new Lexer(sql).tokenize()).parse();
    }

    @Test
    @DisplayName("builds simple Scan and Project")
    void simpleScanAndProject() {
        SelectStmt stmt = parse("SELECT id, name FROM users");
        LogicalPlanNode plan = LogicalPlanBuilder.build(stmt, catalog);

        assertThat(plan).isInstanceOf(LogicalProject.class);
        LogicalProject project = (LogicalProject) plan;
        assertThat(project.projectItems()).hasSize(2);
        assertThat(project.child()).isInstanceOf(LogicalScan.class);
        LogicalScan scan = (LogicalScan) project.child();
        assertThat(scan.tableName()).isEqualTo("users");
    }

    @Test
    @DisplayName("builds Scan with Filter")
    void scanWithFilter() {
        SelectStmt stmt = parse("SELECT * FROM users WHERE age > 18");
        LogicalPlanNode plan = LogicalPlanBuilder.build(stmt, catalog);

        assertThat(plan).isInstanceOf(LogicalFilter.class);
        LogicalFilter filter = (LogicalFilter) plan;
        assertThat(filter.predicates()).hasSize(1);
        assertThat(filter.child()).isInstanceOf(LogicalScan.class);
    }

    @Test
    @DisplayName("builds Join tree")
    void joinTree() {
        SelectStmt stmt = parse("SELECT users.name, orders.amount FROM users JOIN orders ON users.id = orders.user_id");
        LogicalPlanNode plan = LogicalPlanBuilder.build(stmt, catalog);

        assertThat(plan).isInstanceOf(LogicalProject.class);
        LogicalPlanNode child = ((LogicalProject) plan).child();
        assertThat(child).isInstanceOf(LogicalJoin.class);

        LogicalJoin join = (LogicalJoin) child;
        assertThat(join.joinType()).isEqualTo(JoinType.INNER);
        assertThat(join.left()).isInstanceOf(LogicalScan.class);
        assertThat(join.right()).isInstanceOf(LogicalScan.class);
        assertThat(((LogicalScan) join.left()).tableName()).isEqualTo("users");
        assertThat(((LogicalScan) join.right()).tableName()).isEqualTo("orders");
    }

    @Test
    @DisplayName("builds Aggregate and Group By")
    void aggregateAndGroupBy() {
        SelectStmt stmt = parse("SELECT age, COUNT(*) AS cnt FROM users GROUP BY age");
        LogicalPlanNode plan = LogicalPlanBuilder.build(stmt, catalog);

        assertThat(plan).isInstanceOf(LogicalAggregate.class);
        LogicalAggregate agg = (LogicalAggregate) plan;
        assertThat(agg.groupBy()).contains("age");
        assertThat(agg.child()).isInstanceOf(LogicalScan.class);
    }

    @Test
    @DisplayName("builds Sort and Limit pipeline")
    void sortAndLimitPipeline() {
        SelectStmt stmt = parse("SELECT id, name FROM users ORDER BY age DESC LIMIT 5 OFFSET 2");
        LogicalPlanNode plan = LogicalPlanBuilder.build(stmt, catalog);

        assertThat(plan).isInstanceOf(LogicalLimit.class);
        LogicalLimit limit = (LogicalLimit) plan;
        assertThat(limit.limit()).contains(5);
        assertThat(limit.offset()).contains(2);

        assertThat(limit.child()).isInstanceOf(LogicalProject.class);
        LogicalProject project = (LogicalProject) limit.child();

        assertThat(project.child()).isInstanceOf(LogicalSort.class);
        LogicalSort sort = (LogicalSort) project.child();
        assertThat(sort.orderItems()).hasSize(1);
        assertThat(sort.orderItems().get(0).ascending()).isFalse();

        assertThat(sort.child()).isInstanceOf(LogicalScan.class);
    }
}
