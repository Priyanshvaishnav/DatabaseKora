package dev.koradb.observability.integration;

import dev.koradb.KoraDB;
import dev.koradb.catalog.DiskCatalog;
import dev.koradb.execution.QueryResult;
import dev.koradb.observability.ObservabilityEngine;
import dev.koradb.observability.exporter.MetricsExporterConfig;
import dev.koradb.observability.tracing.Span;
import dev.koradb.observability.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityIntegrationTest {

    @TempDir
    Path dbDir;

    private KoraDB db;
    private ObservabilityEngine engine;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() throws Exception {
        db = new KoraDB(dbDir);
        engine = new ObservabilityEngine();

        DiskCatalog catalog = (DiskCatalog) db.getCatalog();
        engine.bindLogManager(catalog::getLogManager);
        engine.bindTransactionManager(db.getTransactionManager());
        engine.bindBufferPools(() -> {
            var openFiles = catalog.getOpenTableFiles();
            return openFiles.stream()
                    .filter(tf -> tf.getBufferPool() != null)
                    .map(tf -> tf.getBufferPool().getStats())
                    .toList();
        });

        engine.startHttpExporter(MetricsExporterConfig.of("localhost", 0));
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) engine.close();
        if (db != null) db.close();
    }

    @Test
    @DisplayName("End-to-end integration: SQL queries, SHOW commands, Prometheus scrape, and distributed tracing")
    void testEndToEndObservability() throws Exception {
        // 1. Execute SQL DDL and DML queries
        long t0 = System.nanoTime();
        db.execute("CREATE DATABASE bank");
        engine.recordQuery("CREATE DATABASE bank", System.nanoTime() - t0, true);

        t0 = System.nanoTime();
        db.execute("USE bank");
        engine.recordQuery("USE bank", System.nanoTime() - t0, true);

        t0 = System.nanoTime();
        db.execute("CREATE TABLE accounts (id INT, balance DOUBLE)");
        engine.recordQuery("CREATE TABLE accounts (id INT, balance DOUBLE)", System.nanoTime() - t0, true);

        t0 = System.nanoTime();
        db.execute("INSERT INTO accounts VALUES (1, 1000.0)");
        engine.recordQuery("INSERT INTO accounts VALUES (1, 1000.0)", System.nanoTime() - t0, true);

        t0 = System.nanoTime();
        QueryResult selectRes = db.execute("SELECT * FROM accounts");
        engine.recordQuery("SELECT * FROM accounts", System.nanoTime() - t0, true);
        assertThat(selectRes.rows()).hasSize(1);

        // 2. Test SHOW BUFFER POOL STATUS
        QueryResult bpStatus = db.execute("SHOW BUFFER POOL STATUS");
        assertThat(bpStatus.columns()).containsExactly("metric", "value");
        assertThat(bpStatus.rows()).isNotEmpty();
        assertThat(bpStatus.rows().stream().anyMatch(r -> "hits".equals(r.get("metric")))).isTrue();
        assertThat(bpStatus.rows().stream().anyMatch(r -> "hit_ratio".equals(r.get("metric")))).isTrue();

        // 3. Test SHOW TRANSACTIONS
        QueryResult txnStatus = db.execute("SHOW TRANSACTIONS");
        assertThat(txnStatus.columns()).contains("txn_id", "state", "isolation_level");

        // 4. Test SHOW METRICS
        QueryResult metricsStatus = db.execute("SHOW METRICS");
        assertThat(metricsStatus.columns()).containsExactly("name", "type", "value");
        assertThat(metricsStatus.rows().stream()
                .anyMatch(r -> String.valueOf(r.get("name")).contains("koradb_queries_total"))).isTrue();

        // 5. Test Prometheus HTTP Exporter endpoint
        int port = engine.getHttpServer().getPort();
        HttpRequest metricsReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/metrics"))
                .GET()
                .build();
        HttpResponse<String> metricsResp = httpClient.send(metricsReq, HttpResponse.BodyHandlers.ofString());

        assertThat(metricsResp.statusCode()).isEqualTo(200);
        String body = metricsResp.body();
        assertThat(body).contains("koradb_queries_total{type=\"SELECT\"} 1");
        assertThat(body).contains("koradb_queries_total{type=\"INSERT\"} 1");
        assertThat(body).contains("koradb_queries_total{type=\"DDL\"} 3");
        assertThat(body).contains("koradb_wal_flushes_total");

        // 6. Test Distributed Query Tracing with nested spans
        Tracer tracer = engine.getTracer();
        Span rootSpan = tracer.spanBuilder("db.query")
                .setAttribute("db.statement", "SELECT * FROM accounts")
                .start();

        try (Tracer.Scope rootScope = tracer.withSpan(rootSpan)) {
            Span parseSpan = tracer.startSpan("db.parse");
            parseSpan.end();

            Span planSpan = tracer.startSpan("db.plan");
            planSpan.end();

            Span execSpan = tracer.startSpan("db.execute");
            execSpan.setAttribute("db.rows_returned", 1);
            execSpan.end();
        } finally {
            rootSpan.end();
        }

        List<Span> finished = engine.getTraceExporter().findByTraceId(rootSpan.getTraceId());
        assertThat(finished).hasSize(4);
        assertThat(finished.stream().map(Span::getName)).contains("db.query", "db.parse", "db.plan", "db.execute");
    }
}
