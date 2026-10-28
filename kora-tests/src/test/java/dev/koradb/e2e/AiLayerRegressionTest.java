package dev.koradb.e2e;

import com.sun.net.httpserver.HttpServer;
import dev.koradb.KoraDB;
import dev.koradb.ai.AiExecutionPolicy;
import dev.koradb.ai.KoraAi;
import dev.koradb.ai.model.AiQueryResult;
import dev.koradb.ai.model.OptimizationReport;
import dev.koradb.ai.model.TranslationResult;
import dev.koradb.ai.provider.LlmClient;
import dev.koradb.ai.provider.LlmRequest;
import dev.koradb.ai.provider.LlmResponse;
import dev.koradb.ai.provider.OpenAiLlmClient;
import dev.koradb.ai.provider.RuleBasedLlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.koradb.e2e.E2e.bag;
import static dev.koradb.e2e.E2e.count;
import static org.assertj.core.api.Assertions.assertThat;

/** AI-Layer rows of the end-to-end report that were FAIL or WARN (store schema from the report). */
class AiLayerRegressionTest {

    private Path dir;
    private KoraDB db;
    private HttpServer fakeProvider;

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("kora-e2e-ai");
        db = new KoraDB(dir);
        db.execute("CREATE DATABASE store");
        db.execute("USE store");
        db.execute("CREATE TABLE products (pid INT, name VARCHAR, category VARCHAR, price DOUBLE, stock INT)");
        db.execute("CREATE TABLE customers (id INT, name VARCHAR, city VARCHAR, age INT, signup_year INT)");
        db.execute("CREATE TABLE orders (oid INT, customer_id INT, amount DOUBLE, status VARCHAR)");
        db.execute("CREATE TABLE scratch (v INT)");
        db.execute("CREATE TABLE victim (v INT)");
        db.execute("CREATE TABLE victim2 (v INT)");
        String[] cats = {"books", "electronics", "toys"};
        for (int i = 1; i <= 30; i++) {
            db.execute("INSERT INTO products VALUES (" + i + ", 'prod" + i + "', '" + cats[i % 3] + "', " + (i * 15.0) + ", " + (i % 7) + ")");
        }
        String[] cities = {"Pune", "Delhi", "New York", "Goa"};
        String[] names = {"alice", "bob", "carol", "dave"};
        for (int i = 1; i <= 40; i++) {
            db.execute("INSERT INTO customers VALUES (" + i + ", '" + names[i % 4] + i + "', '" + cities[i % 4] + "', " + (18 + i) + ", " + (2018 + i % 9) + ")");
        }
        db.execute("INSERT INTO customers VALUES (41, 'alice', 'Pune', 33, 2025)");
        for (int i = 1; i <= 120; i++) {
            db.execute("INSERT INTO orders VALUES (" + i + ", " + (1 + i % 12) + ", " + (i * 4.0) + ", '" + (i % 3 == 0 ? "shipped" : "pending") + "')");
        }
        db.execute("INSERT INTO scratch VALUES (1)");
    }

    @AfterEach
    void tearDown() {
        if (fakeProvider != null) fakeProvider.stop(0);
        db.close();
        E2e.deleteTree(dir);
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

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "AI-NL-16 | customers older than 50                          | SELECT * FROM customers WHERE age > 50",
            "AI-NL-17 | find users older than 25                         | SELECT * FROM customers WHERE age > 25",
            "AI-NL-18 | find all customers who signed up after 2024      | SELECT * FROM customers WHERE signup_year > 2024",
            "AI-NL-22 | find customers where city is New York            | SELECT * FROM customers WHERE city = 'New York'",
            "AI-NL-24 | select * from customers where name = 'alice'     | SELECT * FROM customers WHERE name = 'alice'",
            "AI-NL-25 | SELECT name FROM customers WHERE city = 'Pune'   | SELECT name FROM customers WHERE city = 'Pune'",
            "AI-NL-26 | Show all orders that are pending                 | SELECT * FROM orders WHERE status = 'pending'",
            "AI-NL-27 | what is the most expensive product               | SELECT * FROM products ORDER BY price DESC LIMIT 1",
            "AI-NL-28 | how many products are in the books category      | SELECT COUNT(*) FROM products WHERE category = 'books'",
            "AI-NL-29 | list orders for customer 7                       | SELECT * FROM orders WHERE customer_id = 7",
            "AI-NL-30 | show me products that cost less than 20          | SELECT * FROM products WHERE price < 20",
            "AI-NL-31 | find product where pid = 10                      | SELECT * FROM products WHERE pid = 10",
            "AI-NL-33 | find orders where amount < -1                    | SELECT * FROM orders WHERE amount < -1",
            "AI-NL-34 | total revenue from shipped orders                | SELECT SUM(amount) FROM orders WHERE status = 'shipped'",
            "AI-NL-35 | customers in Delhi older than 30                 | SELECT * FROM customers WHERE city = 'Delhi' AND age > 30",
    })
    void naturalLanguageGivesTheExpectedRows(String id, String prompt, String expectedSql) {
        AiQueryResult r = ai().ask(prompt);
        assertThat(r.executed()).as(id + " generated: " + r.generatedSql() + " error: " + r.errorMessage()).isTrue();
        assertThat(bag(r.queryResult())).as(id + " generated: " + r.generatedSql()).isEqualTo(bag(db.execute(expectedSql)));
    }

    @Test
    @DisplayName("AI-NL-32 \"show tables\" runs SHOW TABLES")
    void showTables() {
        AiQueryResult r = ai().ask("show tables");
        assertThat(r.executed()).as(r.errorMessage()).isTrue();
        assertThat(r.queryResult().rows()).hasSize(6);
    }

    @Test
    @DisplayName("AI-SAFE-01 A natural-language delete is not executed by default")
    void deleteNotExecutedByDefault() {
        AiQueryResult r = ai().ask("delete from scratch where v = 1");
        assertThat(r.executed()).isFalse();
        assertThat(count(db, "SELECT COUNT(*) FROM scratch")).isEqualTo(1);
    }

    @Test
    @DisplayName("AI-SAFE-02 A natural-language drop table is not executed by default (nor with ALLOW_WRITES)")
    void dropNotExecuted() {
        assertThat(ai().ask("drop table victim").executed()).isFalse();
        assertThat(ai().ask("drop table victim", AiExecutionPolicy.ALLOW_WRITES).executed()).isFalse();
        assertThat(count(db, "SELECT COUNT(*) FROM victim")).isZero();   // table still exists
    }

    @Test
    @DisplayName("AI-SAFE-03 A benign question answered with DROP TABLE is never executed, even with ALLOW_ALL")
    void injectedDropNotExecuted() {
        AiQueryResult r = KoraAi.create(db, answering("DROP TABLE victim2")).ask("how many products are there", AiExecutionPolicy.ALLOW_ALL);
        assertThat(r.executed()).isFalse();
        assertThat(count(db, "SELECT COUNT(*) FROM victim2")).isZero();
    }

    @Test
    @DisplayName("AI-SAFE-05 Rule-based passthrough keeps the case of string literals")
    void passthroughKeepsLiteralCase() {
        TranslationResult t = ai().translate("select * from customers where name = 'alice'");
        assertThat(t.generatedSql()).contains("'alice'");
    }

    @Test
    @DisplayName("AI-SAFE-06 Generated SQL naming a table or column that does not exist is invalid")
    void hallucinatedNamesRejected() {
        assertThat(KoraAi.create(db, answering("SELECT * FROM employees WHERE l = 't'")).translate("list employees").isValid()).isFalse();
        assertThat(KoraAi.create(db, answering("SELECT * FROM customers WHERE ghost = 1")).translate("list customers").isValid()).isFalse();
    }

    @Test
    @DisplayName("AI-HTTP-04 HTTP 500 from the provider is a clean failure from ask(), not an exception")
    void provider500IsCleanFailure() throws Exception {
        int port = startFakeProvider(500, "{\"error\":{\"message\":\"boom\"}}", new AtomicInteger());
        AiQueryResult r = KoraAi.create(db, openAi(port)).ask("how many orders");
        assertThat(r.executed()).isFalse();
        assertThat(r.errorMessage()).contains("500");
    }

    @Test
    @DisplayName("AI-HTTP-05 HTTP 429 is retried with backoff and then reported cleanly")
    void provider429RetriedThenReported() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        int port = startFakeProvider(429, "{\"error\":{\"message\":\"rate limited\"}}", calls);
        TranslationResult t = KoraAi.create(db, openAi(port)).translate("how many orders");
        assertThat(t.isValid()).isFalse();
        assertThat(t.errorMessage()).contains("429");
        assertThat(calls.get()).isGreaterThan(1);
    }

    @Test
    @DisplayName("AI-ADV-04 The advisor does not recommend an index on a column that does not exist")
    void advisorIgnoresMissingColumns() {
        OptimizationReport rep = ai().advise("SELECT * FROM products WHERE ghost_col = 1");
        assertThat(rep.recommendedIndexDdls()).noneMatch(ddl -> ddl.toLowerCase().contains("ghost_col"));
    }

    private OpenAiLlmClient openAi(int port) {
        return new OpenAiLlmClient("test-key", "http://127.0.0.1:" + port + "/v1/chat/completions", "m", Duration.ofSeconds(5));
    }

    private int startFakeProvider(int status, String body, AtomicInteger calls) throws Exception {
        fakeProvider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeProvider.createContext("/", ex -> {
            calls.incrementAndGet();
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Retry-After", "0");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        fakeProvider.start();
        return fakeProvider.getAddress().getPort();
    }
}
