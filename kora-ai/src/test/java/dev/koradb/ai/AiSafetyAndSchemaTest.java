package dev.koradb.ai;

import dev.koradb.KoraDB;
import dev.koradb.ai.model.AiQueryResult;
import dev.koradb.ai.model.TranslationResult;
import dev.koradb.ai.provider.LlmClient;
import dev.koradb.ai.provider.LlmRequest;
import dev.koradb.ai.provider.LlmResponse;
import dev.koradb.ai.provider.RuleBasedLlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/** ask() execution policy, schema-checked translation and schema-aware rule-based translation. */
class AiSafetyAndSchemaTest {

    private Path tempDir;
    private KoraDB db;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("koradb-ai-safety");
        db = new KoraDB(tempDir);
        db.execute("CREATE DATABASE shop");
        db.execute("USE shop");
        db.execute("CREATE TABLE customers (id INT, name VARCHAR, city VARCHAR, age INT, signup_year INT)");
        db.execute("CREATE TABLE orders (oid INT, customer_id INT, amount DOUBLE, status VARCHAR)");
        String[] cities = {"Pune", "Delhi", "New York"};
        for (int i = 1; i <= 30; i++) {
            db.execute("INSERT INTO customers VALUES (" + i + ", 'c" + i + "', '" + cities[i % 3] + "', " + (18 + i * 2) + ", " + (2018 + i % 8) + ")");
        }
        for (int i = 1; i <= 40; i++) {
            db.execute("INSERT INTO orders VALUES (" + i + ", " + (1 + i % 30) + ", " + (i * 10.0) + ", '" + (i % 4 == 0 ? "shipped" : "pending") + "')");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        try (var s = Files.walk(tempDir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private KoraAi ai() {
        return KoraAi.create(db, new RuleBasedLlmClient());
    }

    private static LlmClient answering(String sql) {
        return new LlmClient() {
            public String getProviderName() { return "fake"; }
            public String getModelName() { return "fake"; }
            public LlmResponse complete(LlmRequest r) { return LlmResponse.of("```sql\n" + sql + "\n```", "fake", "fake", 1); }
        };
    }

    private long count(String sql) {
        return ((Number) db.execute(sql).rows().get(0).values().values().iterator().next()).longValue();
    }

    @Test
    @DisplayName("ask() is read-only by default: a translated DELETE is returned, not executed")
    void askIsReadOnlyByDefault() {
        AiQueryResult r = ai().ask("delete orders where status = shipped");
        assertThat(r.executed()).isFalse();
        assertThat(r.generatedSql()).startsWith("DELETE FROM orders");
        assertThat(r.errorMessage()).contains("read-only");
        assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(40);
    }

    @Test
    @DisplayName("ALLOW_WRITES executes the requested DELETE")
    void allowWritesExecutesRequestedChange() {
        AiQueryResult r = ai().ask("delete orders where status = shipped", AiExecutionPolicy.ALLOW_WRITES);
        assertThat(r.executed()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(30);
    }

    @Test
    @DisplayName("A write the request did not ask for is never executed (prompt injection / wrong answer)")
    void mismatchedWriteRefused() {
        AiQueryResult r = KoraAi.create(db, answering("DELETE FROM orders;")).ask("how many orders are there", AiExecutionPolicy.ALLOW_ALL);
        assertThat(r.executed()).isFalse();
        assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(40);
    }

    @Test
    @DisplayName("DDL needs ALLOW_ALL")
    void ddlNeedsAllowAll() {
        AiQueryResult r = ai().ask("drop table orders", AiExecutionPolicy.ALLOW_WRITES);
        assertThat(r.executed()).isFalse();
        assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(40);
    }

    @Test
    @DisplayName("Generated SQL naming a table or column that does not exist is invalid")
    void hallucinatedNamesInvalid() {
        assertThat(KoraAi.create(db, answering("SELECT * FROM employees;")).translate("list employees").isValid()).isFalse();
        TranslationResult tr = KoraAi.create(db, answering("SELECT * FROM orders WHERE price > 3;")).translate("orders over 3");
        assertThat(tr.isValid()).isFalse();
        assertThat(tr.errorMessage()).contains("price");
        assertThat(ai().translate("show me the employees list").isValid()).isFalse();
    }

    @Test
    @DisplayName("Provider failures become an invalid result, not an exception")
    void providerFailureIsResult() {
        LlmClient failing = new LlmClient() {
            public String getProviderName() { return "down"; }
            public String getModelName() { return "down"; }
            public LlmResponse complete(LlmRequest r) { throw new RuntimeException("HTTP 503"); }
        };
        AiQueryResult r = KoraAi.create(db, failing).ask("how many orders");
        assertThat(r.executed()).isFalse();
        assertThat(r.errorMessage()).contains("HTTP 503");
    }

    @Test
    @DisplayName("Schema-aware rule-based translation uses real columns and known values")
    void schemaAwareTranslation() {
        KoraAi ai = ai();
        assertThat(ai.translate("customers older than 50").generatedSql()).isEqualTo("SELECT * FROM customers WHERE age > 50;");
        assertThat(ai.translate("find users older than 25").generatedSql()).isEqualTo("SELECT * FROM customers WHERE age > 25;");
        assertThat(ai.translate("customers who signed up after 2024").generatedSql()).isEqualTo("SELECT * FROM customers WHERE signup_year > 2024;");
        assertThat(ai.translate("find customers where city is new york").generatedSql()).isEqualTo("SELECT * FROM customers WHERE city = 'New York';");
        assertThat(ai.translate("Show all orders that are pending").generatedSql()).isEqualTo("SELECT * FROM orders WHERE status = 'pending';");
        assertThat(ai.translate("list orders for customer 7").generatedSql()).isEqualTo("SELECT * FROM orders WHERE customer_id = 7;");
        assertThat(ai.translate("total revenue from shipped orders").generatedSql()).isEqualTo("SELECT SUM(amount) FROM orders WHERE status = 'shipped';");
        assertThat(ai.translate("customers in Delhi older than 30").generatedSql()).isEqualTo("SELECT * FROM customers WHERE age > 30 AND city = 'Delhi';");
        assertThat(ai.translate("oldest customer").generatedSql()).isEqualTo("SELECT * FROM customers ORDER BY age DESC LIMIT 1;");
        assertThat(ai.translate("count orders by status").generatedSql()).isEqualTo("SELECT status, COUNT(*) FROM orders GROUP BY status;");
        assertThat(ai.translate("select * from customers where name = 'c3'").generatedSql()).isEqualTo("select * from customers where name = 'c3';");
    }

    @Test
    @DisplayName("SHOW TABLES / DESCRIBE translate and execute")
    void metadataCommands() {
        AiQueryResult r = ai().ask("show tables");
        assertThat(r.executed()).isTrue();
        assertThat(r.queryResult().rows()).hasSize(2);
        assertThat(ai().ask("describe orders").queryResult().rows()).hasSize(4);
    }

    @Test
    @DisplayName("Sample values are not sent to remote providers unless enabled")
    void sampleValuesOptInForRemote() {
        StringBuilder seen = new StringBuilder();
        LlmClient remote = new LlmClient() {
            public String getProviderName() { return "remote"; }
            public String getModelName() { return "m"; }
            public LlmResponse complete(LlmRequest r) { seen.append(r.systemPrompt()); return LlmResponse.of("SELECT * FROM orders;", "m", "remote", 1); }
        };
        KoraAi.create(db, remote).translate("pending orders");
        assertThat(seen.toString()).doesNotContain("'pending'");
        seen.setLength(0);
        KoraAi.create(db, remote).shareSampleValues(true).translate("pending orders");
        assertThat(seen.toString()).contains("'pending'");
    }
}
