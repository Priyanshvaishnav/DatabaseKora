package dev.koradb.planner.cost;

import dev.koradb.catalog.ColumnStatistics;
import dev.koradb.catalog.TableStatistics;
import dev.koradb.sql.ast.BinaryExpr;
import dev.koradb.sql.ast.ColumnRefExpr;
import dev.koradb.sql.ast.LiteralExpr;
import dev.koradb.sql.ast.Operator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("CostModel")
class CostModelTest {

    @Test
    @DisplayName("estimates equality selectivity based on column NDV")
    void equalitySelectivity() {
        TableStatistics stats = new TableStatistics("users", 1000, 20, Map.of(
                "id", new ColumnStatistics("id", 1000, 0, 1, 1000),
                "city", new ColumnStatistics("city", 5, 0, null, null)
        ));

        BinaryExpr idEq = new BinaryExpr(new ColumnRefExpr("id"), Operator.EQ, new LiteralExpr(42));
        double idSel = CostModel.estimateSelectivity(idEq, stats);
        assertThat(idSel).isCloseTo(1.0 / 1000.0, within(0.0001));

        BinaryExpr cityEq = new BinaryExpr(new ColumnRefExpr("city"), Operator.EQ, new LiteralExpr("Paris"));
        double citySel = CostModel.estimateSelectivity(cityEq, stats);
        assertThat(citySel).isCloseTo(1.0 / 5.0, within(0.0001));
    }

    @Test
    @DisplayName("estimates inequality selectivity at standard 0.33")
    void inequalitySelectivity() {
        BinaryExpr gt = new BinaryExpr(new ColumnRefExpr("age"), Operator.GT, new LiteralExpr(18));
        double sel = CostModel.estimateSelectivity(gt, null);
        assertThat(sel).isCloseTo(0.33, within(0.001));
    }

    @Test
    @DisplayName("estimates conjunction selectivity as product of independents")
    void conjunctionSelectivity() {
        TableStatistics stats = new TableStatistics("users", 1000, 20, Map.of(
                "city", new ColumnStatistics("city", 10, 0, null, null)
        ));

        BinaryExpr eq = new BinaryExpr(new ColumnRefExpr("city"), Operator.EQ, new LiteralExpr("NYC"));
        BinaryExpr gt = new BinaryExpr(new ColumnRefExpr("age"), Operator.GT, new LiteralExpr(25));

        double conjSel = CostModel.estimateConjunctionSelectivity(List.of(eq, gt), stats);
        assertThat(conjSel).isCloseTo(0.1 * 0.33, within(0.001));
    }

    @Test
    @DisplayName("computes sequential scan cost correctly")
    void seqScanCost() {
        PlanCost cost = CostModel.estimateSeqScanCost(10, 1000);
        // 10 pages * 1.0 + 1000 rows * 0.01 = 10 + 10 = 20.0
        assertThat(cost.totalCost()).isEqualTo(20.0);
        assertThat(cost.estimatedRows()).isEqualTo(1000);
        assertThat(cost.estimatedPages()).isEqualTo(10);
    }

    @Test
    @DisplayName("computes index scan cost correctly")
    void indexScanCost() {
        // Point lookup returning 1 row: height 3 * 1.0 + 1 * 4.0 + 1 * 0.01 = 7.01
        PlanCost cost = CostModel.estimateIndexScanCost(3, 1);
        assertThat(cost.totalCost()).isEqualTo(7.01);
        assertThat(cost.estimatedRows()).isEqualTo(1);
    }
}
