package dev.koradb.execution;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.exception.ExecutionException;
import dev.koradb.exception.SchemaException;
import dev.koradb.sql.ast.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ExpressionEvaluator}.
 *
 * <p>Tests that each comparison operator works correctly for each supported
 * type, and that numeric promotion and null handling work as specified.
 */
@DisplayName("ExpressionEvaluator")
class ExpressionEvaluatorTest {

    private ExpressionEvaluator evaluator;
    private Row row;

    @BeforeEach
    void setUp() {
        Schema schema = new Schema(List.of(
                new ColumnDef("age",     DataType.INT),
                new ColumnDef("salary",  DataType.DOUBLE),
                new ColumnDef("name",    DataType.VARCHAR),
                new ColumnDef("active",  DataType.BOOLEAN),
                new ColumnDef("score",   DataType.BIGINT)
        ));
        evaluator = new ExpressionEvaluator(schema);

        Map<String, Object> vals = new LinkedHashMap<>();
        vals.put("age",    30);
        vals.put("salary", 95000.0);
        vals.put("name",   "Alice");
        vals.put("active", true);
        vals.put("score",  999_999_999L);
        row = new Row(vals);
    }

    private BinaryExpr pred(String col, Operator op, Object val) {
        return new BinaryExpr(new ColumnRefExpr(col), op, new LiteralExpr(val));
    }

    // =========================================================================
    // INT comparisons
    // =========================================================================

    @Nested
    @DisplayName("INT comparisons")
    class IntComparisons {

        @Test void eq_match()    { assertThat(evaluator.evaluate(pred("age", Operator.EQ,  30), row)).isTrue(); }
        @Test void eq_noMatch()  { assertThat(evaluator.evaluate(pred("age", Operator.EQ,  31), row)).isFalse(); }
        @Test void neq_match()   { assertThat(evaluator.evaluate(pred("age", Operator.NEQ, 31), row)).isTrue(); }
        @Test void lt_match()    { assertThat(evaluator.evaluate(pred("age", Operator.LT,  31), row)).isTrue(); }
        @Test void lt_noMatch()  { assertThat(evaluator.evaluate(pred("age", Operator.LT,  30), row)).isFalse(); }
        @Test void gt_match()    { assertThat(evaluator.evaluate(pred("age", Operator.GT,  29), row)).isTrue(); }
        @Test void lte_equal()   { assertThat(evaluator.evaluate(pred("age", Operator.LTE, 30), row)).isTrue(); }
        @Test void lte_less()    { assertThat(evaluator.evaluate(pred("age", Operator.LTE, 31), row)).isTrue(); }
        @Test void gte_equal()   { assertThat(evaluator.evaluate(pred("age", Operator.GTE, 30), row)).isTrue(); }
        @Test void gte_greater() { assertThat(evaluator.evaluate(pred("age", Operator.GTE, 29), row)).isTrue(); }
    }

    // =========================================================================
    // String comparisons
    // =========================================================================

    @Nested
    @DisplayName("VARCHAR comparisons")
    class StringComparisons {

        @Test void eq_match()   { assertThat(evaluator.evaluate(pred("name", Operator.EQ,  "Alice"), row)).isTrue(); }
        @Test void eq_noMatch() { assertThat(evaluator.evaluate(pred("name", Operator.EQ,  "Bob"),   row)).isFalse(); }
        @Test void neq_match()  { assertThat(evaluator.evaluate(pred("name", Operator.NEQ, "Bob"),   row)).isTrue(); }
        @Test void lt_match()   { assertThat(evaluator.evaluate(pred("name", Operator.LT,  "Bob"),   row)).isTrue(); }  // Alice < Bob
        @Test void gt_noMatch() { assertThat(evaluator.evaluate(pred("name", Operator.GT,  "Bob"),   row)).isFalse(); }
    }

    // =========================================================================
    // Boolean comparisons
    // =========================================================================

    @Nested
    @DisplayName("BOOLEAN comparisons")
    class BooleanComparisons {

        @Test void eq_true()  { assertThat(evaluator.evaluate(pred("active", Operator.EQ, true),  row)).isTrue(); }
        @Test void eq_false() { assertThat(evaluator.evaluate(pred("active", Operator.EQ, false), row)).isFalse(); }
        @Test void neq()      { assertThat(evaluator.evaluate(pred("active", Operator.NEQ, false), row)).isTrue(); }
    }

    // =========================================================================
    // DOUBLE comparisons
    // =========================================================================

    @Nested
    @DisplayName("DOUBLE comparisons")
    class DoubleComparisons {

        @Test void eq_exact() { assertThat(evaluator.evaluate(pred("salary", Operator.EQ, 95000.0), row)).isTrue(); }
        @Test void gt()       { assertThat(evaluator.evaluate(pred("salary", Operator.GT, 90000.0), row)).isTrue(); }
        @Test void lt()       { assertThat(evaluator.evaluate(pred("salary", Operator.LT, 100000.0), row)).isTrue(); }
    }

    // =========================================================================
    // Numeric promotion
    // =========================================================================

    @Nested
    @DisplayName("Numeric promotion")
    class NumericPromotion {

        @Test
        @DisplayName("INT column compared with DOUBLE literal is promoted")
        void intVsDouble() {
            // age = 30, compare with 30.0 (double) — should promote int to double
            assertThat(evaluator.evaluate(pred("age", Operator.EQ, 30.0), row)).isTrue();
        }

        @Test
        @DisplayName("BIGINT column compared with INT literal is promoted")
        void bigintVsInt() {
            assertThat(evaluator.evaluate(pred("score", Operator.GT, 1000), row)).isTrue();
        }
    }

    // =========================================================================
    // NULL handling
    // =========================================================================

    @Nested
    @DisplayName("NULL handling")
    class NullHandling {

        @Test
        @DisplayName("comparison with null column value returns false")
        void nullColumnValue() {
            Map<String, Object> vals = new LinkedHashMap<>();
            vals.put("age", null); // null value
            Row nullRow = new Row(vals);

            Schema schema = new Schema(List.of(new ColumnDef("age", DataType.INT)));
            ExpressionEvaluator ev = new ExpressionEvaluator(schema);

            assertThat(ev.evaluate(pred("age", Operator.EQ, 30), nullRow)).isFalse();
            assertThat(ev.evaluate(pred("age", Operator.GT, 0),  nullRow)).isFalse();
        }
    }

    // =========================================================================
    // Error cases
    // =========================================================================

    @Nested
    @DisplayName("Error cases")
    class ErrorCases {

        @Test
        @DisplayName("Unknown column throws SchemaException")
        void unknownColumn() {
            assertThatThrownBy(() -> evaluator.evaluate(pred("phone", Operator.EQ, "123"), row))
                    .isInstanceOf(SchemaException.class)
                    .hasMessageContaining("phone");
        }

        @Test
        @DisplayName("String vs INT type mismatch throws ExecutionException")
        void typeMismatch() {
            // Trying to compare age (INT, value=30) with a String literal "thirty"
            assertThatThrownBy(() -> evaluator.evaluate(pred("age", Operator.EQ, "thirty"), row))
                    .isInstanceOf(ExecutionException.class);
        }
    }
}
