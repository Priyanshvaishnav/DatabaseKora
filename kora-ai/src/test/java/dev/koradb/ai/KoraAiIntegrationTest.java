package dev.koradb.ai;

import dev.koradb.KoraDB;
import dev.koradb.ai.model.AiQueryResult;
import dev.koradb.ai.model.ExplanationResult;
import dev.koradb.ai.model.OptimizationReport;
import dev.koradb.ai.model.TranslationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KoraAiIntegrationTest {

    private Path tempDir;
    private KoraDB db;
    private KoraAi ai;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("koradb-ai-test");
        db = new KoraDB(tempDir);
        db.execute("CREATE DATABASE prod;");
        db.execute("USE prod;");
        db.execute("CREATE TABLE accounts (id INT, owner VARCHAR, balance DOUBLE);");
        db.execute("INSERT INTO accounts VALUES (1, 'Alice', 1250.0);");
        db.execute("INSERT INTO accounts VALUES (2, 'Bob', 450.0);");
        db.execute("INSERT INTO accounts VALUES (3, 'Charlie', 3200.0);");

        ai = KoraAi.create(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (ai != null) {
            ai.close();
        }
        if (db != null) {
            db.close();
        }
        if (tempDir != null) {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("Translate natural language to SQL grounded by active database schema")
    void testTranslate() {
        TranslationResult result = ai.translate("how many accounts are there");
        assertThat(result.isValid()).isTrue();
        assertThat(result.generatedSql()).isEqualTo("SELECT COUNT(*) FROM accounts;");
    }

    @Test
    @DisplayName("End-to-end ask execution returns live QueryResult")
    void testAskExecution() {
        AiQueryResult res = ai.ask("show all accounts");
        assertThat(res.executed()).isTrue();
        assertThat(res.queryResult()).isNotNull();
        assertThat(res.queryResult().rows()).hasSize(3);
    }

    @Test
    @DisplayName("Ask with count query aggregates correctly")
    void testAskCount() {
        AiQueryResult res = ai.ask("count accounts");
        assertThat(res.executed()).isTrue();
        assertThat(res.queryResult().rows().get(0).get("COUNT(*)")).isEqualTo(3L);
    }

    @Test
    @DisplayName("Explain SQL query")
    void testExplain() {
        ExplanationResult exp = ai.explain("SELECT * FROM accounts WHERE balance > 1000.0;");
        assertThat(exp.explanation()).contains("Intent");
        assertThat(exp.explanation()).contains("Filter Predicate");
    }

    @Test
    @DisplayName("Advise index on unindexed balance column")
    void testAdvise() {
        OptimizationReport rep = ai.advise("SELECT * FROM accounts WHERE balance > 1000.0;");
        assertThat(rep.recommendedIndexDdls())
                .contains("CREATE INDEX idx_accounts_balance ON accounts (balance);");
    }
}
