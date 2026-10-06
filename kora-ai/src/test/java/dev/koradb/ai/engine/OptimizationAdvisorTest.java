package dev.koradb.ai.engine;

import dev.koradb.ai.model.OptimizationReport;
import dev.koradb.ai.provider.RuleBasedLlmClient;
import dev.koradb.ai.schema.DatabaseSchema;
import dev.koradb.ai.schema.TableSchema;
import dev.koradb.sql.ast.ColumnDef;
import dev.koradb.sql.ast.DataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OptimizationAdvisorTest {

    @Test
    @DisplayName("Advise index on unindexed predicate column")
    void testAdviseIndex() {
        OptimizationAdvisor advisor = new OptimizationAdvisor(new RuleBasedLlmClient());
        TableSchema ts = new TableSchema("users", List.of(
                new ColumnDef("id", DataType.INT),
                new ColumnDef("email", DataType.VARCHAR)
        ), List.of()); // No indexes initially
        DatabaseSchema schema = new DatabaseSchema("shop", Map.of("users", ts));

        OptimizationReport report = advisor.advise("SELECT * FROM users WHERE email = 'alice@example.com';", schema);

        assertThat(report.recommendedIndexDdls()).hasSize(1);
        assertThat(report.recommendedIndexDdls().get(0))
                .isEqualTo("CREATE INDEX idx_users_email ON users (email);");
        assertThat(report.analysis()).contains("Bottleneck");
    }

    @Test
    @DisplayName("Do not recommend duplicate index when column is already indexed")
    void testSkipExistingIndex() {
        OptimizationAdvisor advisor = new OptimizationAdvisor(new RuleBasedLlmClient());
        TableSchema ts = new TableSchema("users", List.of(
                new ColumnDef("id", DataType.INT),
                new ColumnDef("email", DataType.VARCHAR)
        ), List.of(new TableSchema.IndexInfo("idx_email", "email", DataType.VARCHAR, false)));
        DatabaseSchema schema = new DatabaseSchema("shop", Map.of("users", ts));

        OptimizationReport report = advisor.advise("SELECT * FROM users WHERE email = 'alice@example.com';", schema);

        // Rule-based check will not add another CREATE INDEX on email
        assertThat(report.recommendedIndexDdls()).doesNotContain("CREATE INDEX idx_users_email ON users (email);");
    }
}
