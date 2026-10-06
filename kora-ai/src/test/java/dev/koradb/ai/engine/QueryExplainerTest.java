package dev.koradb.ai.engine;

import dev.koradb.ai.model.ExplanationResult;
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

class QueryExplainerTest {

    @Test
    @DisplayName("Explain SQL query with filter and join steps")
    void testExplainQuery() {
        QueryExplainer explainer = new QueryExplainer(new RuleBasedLlmClient());
        TableSchema ts = new TableSchema("orders", List.of(
                new ColumnDef("id", DataType.INT),
                new ColumnDef("total", DataType.DOUBLE)
        ), List.of());
        DatabaseSchema schema = new DatabaseSchema("shop", Map.of("orders", ts));

        ExplanationResult res = explainer.explain("SELECT * FROM orders WHERE total > 100.0;", schema);

        assertThat(res.sql()).contains("SELECT * FROM orders WHERE total > 100.0;");
        assertThat(res.explanation()).contains("Intent");
        assertThat(res.explanation()).contains("Scan");
        assertThat(res.explanation()).contains("Filter Predicate");
    }
}
