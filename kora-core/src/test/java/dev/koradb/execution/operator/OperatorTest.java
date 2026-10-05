package dev.koradb.execution.operator;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.catalog.Table;
import dev.koradb.execution.ExpressionEvaluator;
import dev.koradb.sql.ast.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Volcano Operator Tree Unit Tests")
class OperatorTest {

    private Schema createUserSchema() {
        return new Schema(List.of(
                new ColumnDef("id", DataType.INT),
                new ColumnDef("name", DataType.VARCHAR),
                new ColumnDef("age", DataType.INT),
                new ColumnDef("dept", DataType.VARCHAR),
                new ColumnDef("salary", DataType.DOUBLE)
        ));
    }

    private Table createSampleTable() {
        Table table = new Table("employees", createUserSchema());
        table.insertRow(new Row(Map.of("id", 1, "name", "Alice", "age", 30, "dept", "Engineering", "salary", 120000.0)));
        table.insertRow(new Row(Map.of("id", 2, "name", "Bob", "age", 25, "dept", "Engineering", "salary", 95000.0)));
        table.insertRow(new Row(Map.of("id", 3, "name", "Charlie", "age", 35, "dept", "HR", "salary", 80000.0)));
        table.insertRow(new Row(Map.of("id", 4, "name", "Diana", "age", 28, "dept", "Marketing", "salary", 90000.0)));
        table.insertRow(new Row(Map.of("id", 5, "name", "Eve", "age", 40, "dept", "HR", "salary", 110000.0)));
        return table;
    }

    @Nested
    @DisplayName("SeqScanOperator & FilterOperator")
    class ScanAndFilter {

        @Test
        @DisplayName("SeqScanOperator streams all rows")
        void seqScan() {
            Table table = createSampleTable();
            SeqScanOperator scan = new SeqScanOperator(table);
            scan.open();

            int count = 0;
            Row r;
            while ((r = scan.next()) != null) {
                count++;
                assertThat(r.get("id")).isNotNull();
            }
            scan.close();
            assertThat(count).isEqualTo(5);
        }

        @Test
        @DisplayName("FilterOperator filters rows based on predicate")
        void filter() {
            Table table = createSampleTable();
            SeqScanOperator scan = new SeqScanOperator(table);
            BinaryExpr pred = new BinaryExpr(new ColumnRefExpr("dept"), dev.koradb.sql.ast.Operator.EQ, new LiteralExpr("HR"));
            WhereClause where = new WhereClause(List.of(pred));

            FilterOperator filter = new FilterOperator(scan, where, new ExpressionEvaluator(table.getSchema()));
            filter.open();

            List<String> names = new java.util.ArrayList<>();
            Row r;
            while ((r = filter.next()) != null) {
                names.add((String) r.get("name"));
            }
            filter.close();

            assertThat(names).containsExactly("Charlie", "Eve");
        }
    }

    @Nested
    @DisplayName("ProjectOperator & LimitOperator")
    class ProjectAndLimit {

        @Test
        @DisplayName("ProjectOperator selects and renames columns")
        void project() {
            Table table = createSampleTable();
            SeqScanOperator scan = new SeqScanOperator(table);
            List<SelectItem> items = List.of(
                    SelectItem.column("name"),
                    SelectItem.column("salary", "pay")
            );

            ProjectOperator project = new ProjectOperator(scan, items);
            project.open();

            Row first = project.next();
            assertThat(first).isNotNull();
            assertThat(first.values()).containsOnlyKeys("name", "pay");
            assertThat(first.get("name")).isEqualTo("Alice");
            assertThat(first.get("pay")).isEqualTo(120000.0);

            project.close();
        }

        @Test
        @DisplayName("LimitOperator respects LIMIT and OFFSET")
        void limitAndOffset() {
            Table table = createSampleTable();
            SeqScanOperator scan = new SeqScanOperator(table);

            // Skip 2, return 2 (rows at index 2 and 3)
            LimitOperator limit = new LimitOperator(scan, 2, 2);
            limit.open();

            Row r1 = limit.next();
            Row r2 = limit.next();
            Row r3 = limit.next();
            limit.close();

            assertThat(r1).isNotNull();
            assertThat(r1.get("name")).isEqualTo("Charlie");
            assertThat(r2).isNotNull();
            assertThat(r2.get("name")).isEqualTo("Diana");
            assertThat(r3).isNull();
        }
    }

    @Nested
    @DisplayName("SortOperator")
    class Sort {

        @Test
        @DisplayName("Sorts ascending and descending by column")
        void sort() {
            Table table = createSampleTable();
            SeqScanOperator scan = new SeqScanOperator(table);

            // ORDER BY age DESC
            SortOperator sort = new SortOperator(scan, List.of(new OrderItem("age", false)));
            sort.open();

            List<Integer> ages = new java.util.ArrayList<>();
            Row r;
            while ((r = sort.next()) != null) {
                ages.add((Integer) r.get("age"));
            }
            sort.close();

            assertThat(ages).containsExactly(40, 35, 30, 28, 25);
        }
    }

    @Nested
    @DisplayName("Join Operators")
    class Joins {

        private Table createDeptTable() {
            Schema schema = new Schema(List.of(
                    new ColumnDef("dept_name", DataType.VARCHAR),
                    new ColumnDef("building", DataType.VARCHAR)
            ));
            Table table = new Table("departments", schema);
            table.insertRow(new Row(Map.of("dept_name", "Engineering", "building", "B1")));
            table.insertRow(new Row(Map.of("dept_name", "HR", "building", "B2")));
            return table;
        }

        @Test
        @DisplayName("NestedLoopJoinOperator joins matching rows")
        void nestedLoopJoin() {
            Table emp = createSampleTable();
            Table dept = createDeptTable();

            SeqScanOperator left = new SeqScanOperator(emp);
            SeqScanOperator right = new SeqScanOperator(dept);

            BinaryExpr joinCond = new BinaryExpr(
                    new ColumnRefExpr("dept"),
                    dev.koradb.sql.ast.Operator.EQ,
                    new ColumnRefExpr("dept_name")
            );

            NestedLoopJoinOperator join = new NestedLoopJoinOperator(left, right, joinCond);
            join.open();

            int matched = 0;
            Row r;
            while ((r = join.next()) != null) {
                matched++;
                assertThat(r.get("building")).isNotNull();
            }
            join.close();

            // 2 in Eng, 2 in HR -> 4 rows (Diana in Marketing has no matching dept)
            assertThat(matched).isEqualTo(4);
        }

        @Test
        @DisplayName("HashJoinOperator performs fast equality join")
        void hashJoin() {
            Table emp = createSampleTable();
            Table dept = createDeptTable();

            SeqScanOperator left = new SeqScanOperator(emp);
            SeqScanOperator right = new SeqScanOperator(dept);

            HashJoinOperator join = new HashJoinOperator(left, right, "dept", "dept_name");
            join.open();

            int matched = 0;
            Row r;
            while ((r = join.next()) != null) {
                matched++;
                assertThat(r.get("building")).isNotNull();
            }
            join.close();

            assertThat(matched).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("AggregateOperator")
    class Aggregates {

        @Test
        @DisplayName("Computes scalar aggregates across table")
        void scalarAggregates() {
            Table table = createSampleTable();
            SeqScanOperator scan = new SeqScanOperator(table);

            List<SelectItem> items = List.of(
                    SelectItem.aggregate(AggregateType.COUNT, "*", "total"),
                    SelectItem.aggregate(AggregateType.SUM, "salary", "total_salary"),
                    SelectItem.aggregate(AggregateType.AVG, "salary", "avg_salary"),
                    SelectItem.aggregate(AggregateType.MIN, "age", "min_age"),
                    SelectItem.aggregate(AggregateType.MAX, "age", "max_age")
            );

            AggregateOperator agg = new AggregateOperator(scan, items, null);
            agg.open();

            Row result = agg.next();
            assertThat(result).isNotNull();
            assertThat(result.get("total")).isEqualTo(5L);
            assertThat((Double) result.get("total_salary")).isEqualTo(495000.0);
            assertThat((Double) result.get("avg_salary")).isEqualTo(99000.0);
            assertThat((Integer) result.get("min_age")).isEqualTo(25);
            assertThat((Integer) result.get("max_age")).isEqualTo(40);

            assertThat(agg.next()).isNull();
            agg.close();
        }

        @Test
        @DisplayName("Computes grouped aggregates with GROUP BY")
        void groupedAggregates() {
            Table table = createSampleTable();
            SeqScanOperator scan = new SeqScanOperator(table);

            List<SelectItem> items = List.of(
                    SelectItem.column("dept"),
                    SelectItem.aggregate(AggregateType.COUNT, "*", "dept_count"),
                    SelectItem.aggregate(AggregateType.AVG, "salary", "avg_sal")
            );

            AggregateOperator agg = new AggregateOperator(scan, items, "dept");
            agg.open();

            int groupCount = 0;
            Row r;
            while ((r = agg.next()) != null) {
                groupCount++;
                String dept = (String) r.get("dept");
                Long count = (Long) r.get("dept_count");
                if ("Engineering".equals(dept)) {
                    assertThat(count).isEqualTo(2L);
                } else if ("HR".equals(dept)) {
                    assertThat(count).isEqualTo(2L);
                } else if ("Marketing".equals(dept)) {
                    assertThat(count).isEqualTo(1L);
                }
            }
            agg.close();

            assertThat(groupCount).isEqualTo(3);
        }
    }
}
