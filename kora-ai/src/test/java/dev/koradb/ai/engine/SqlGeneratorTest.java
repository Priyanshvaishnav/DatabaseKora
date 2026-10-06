package dev.koradb.ai.engine;

import dev.koradb.ai.model.TranslationResult;
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

class SqlGeneratorTest {

    private final SqlGenerator generator = new SqlGenerator(new RuleBasedLlmClient());

    @Test
    @DisplayName("Generate and validate SQL from prompt")
    void testGenerateValidSql() {
        TableSchema ts = new TableSchema("items", List.of(
                new ColumnDef("id", DataType.INT),
                new ColumnDef("price", DataType.DOUBLE)
        ), List.of());
        DatabaseSchema schema = new DatabaseSchema("shop", Map.of("items", ts));

        TranslationResult result = generator.generate("find items where price > 99.5", schema);

        assertThat(result.isValid()).isTrue();
        assertThat(result.generatedSql()).isEqualTo("SELECT * FROM items WHERE price > 99.5;");
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("Extract SQL from markdown fences")
    void testExtractSql() {
        String fenced = "Here is your SQL query:\n```sql\nSELECT * FROM users;\n```\nHope this helps!";
        assertThat(SqlGenerator.extractSql(fenced)).isEqualTo("SELECT * FROM users;");

        String bare = "SELECT id FROM accounts;";
        assertThat(SqlGenerator.extractSql(bare)).isEqualTo("SELECT id FROM accounts;");
    }

    @Test
    @DisplayName("Validate valid and invalid SQL syntax against KoraDB parser")
    void testValidateSql() {
        assertThat(SqlGenerator.validateSql("SELECT * FROM users;")).isNull();
        assertThat(SqlGenerator.validateSql("SELECT id FROM users;")).isNull();
        assertThat(SqlGenerator.validateSql("")).isNotNull();
        assertThat(SqlGenerator.validateSql("THIS IS NOT SQL;")).isNotNull();
    }
}
