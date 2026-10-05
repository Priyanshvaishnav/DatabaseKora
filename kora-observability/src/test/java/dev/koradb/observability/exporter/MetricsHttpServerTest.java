package dev.koradb.observability.exporter;

import dev.koradb.observability.metrics.Counter;
import dev.koradb.observability.metrics.MetricsRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsHttpServerTest {

    private MetricsRegistry registry;
    private MetricsHttpServer server;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() throws IOException {
        registry = new MetricsRegistry();
        Counter c = registry.counter("koradb_http_requests_total", "Test counter");
        c.inc(42);

        server = new MetricsHttpServer(MetricsExporterConfig.of("localhost", 0), registry);
        server.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("Metrics endpoint exposes Prometheus exposition format over HTTP")
    void testMetricsEndpoint() throws Exception {
        int port = server.getPort();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/metrics"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .contains("text/plain; version=0.0.4; charset=utf-8");
        assertThat(response.body()).contains("koradb_http_requests_total 42");
    }

    @Test
    @DisplayName("Health endpoint returns JSON status UP")
    void testHealthEndpoint() throws Exception {
        int port = server.getPort();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/health"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
}
