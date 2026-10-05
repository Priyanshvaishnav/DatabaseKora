package dev.koradb.observability.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusFormatterTest {

    @Test
    @DisplayName("PrometheusFormatter produces valid Prometheus text exposition output")
    void testFormatRegistry() {
        MetricsRegistry registry = new MetricsRegistry();

        Counter queries = registry.counter("koradb_queries_total", "Total queries executed", "type", "select");
        queries.inc(10);

        Gauge connections = registry.gauge("koradb_active_connections", "Active connections");
        connections.set(3);

        Histogram latency = registry.histogram("koradb_query_duration_seconds", "Query latency", new double[]{0.01, 0.1});
        latency.observe(0.005);
        latency.observe(0.05);

        String output = PrometheusFormatter.format(registry);

        assertThat(output).contains("# HELP koradb_queries_total Total queries executed");
        assertThat(output).contains("# TYPE koradb_queries_total counter");
        assertThat(output).contains("koradb_queries_total{type=\"select\"} 10");

        assertThat(output).contains("# HELP koradb_active_connections Active connections");
        assertThat(output).contains("# TYPE koradb_active_connections gauge");
        assertThat(output).contains("koradb_active_connections 3");

        assertThat(output).contains("# HELP koradb_query_duration_seconds Query latency");
        assertThat(output).contains("# TYPE koradb_query_duration_seconds histogram");
        assertThat(output).contains("koradb_query_duration_seconds_bucket{le=\"0.01\"} 1");
        assertThat(output).contains("koradb_query_duration_seconds_bucket{le=\"0.1\"} 2");
        assertThat(output).contains("koradb_query_duration_seconds_bucket{le=\"+Inf\"} 2");
        assertThat(output).contains("koradb_query_duration_seconds_count 2");
        assertThat(output).contains("koradb_query_duration_seconds_sum");
    }
}
