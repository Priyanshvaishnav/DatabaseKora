package dev.koradb.observability.collectors;

import dev.koradb.observability.metrics.Counter;
import dev.koradb.observability.metrics.Histogram;
import dev.koradb.observability.metrics.MetricsRegistry;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Collects and exposes SQL query execution telemetry (throughput, latency, errors) to {@link MetricsRegistry}.
 */
public final class QueryMetricsCollector {

    private final MetricsRegistry registry;
    private final Counter errorsCounter;
    private final ConcurrentMap<String, Counter> queryCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Histogram> queryHistograms = new ConcurrentHashMap<>();

    public QueryMetricsCollector(MetricsRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.errorsCounter = registry.counter("koradb_query_errors_total", "Total failed query executions");

        // Pre-register common types
        for (String type : new String[]{"SELECT", "INSERT", "UPDATE", "DELETE", "DDL", "OTHER"}) {
            getQueryCounter(type);
            getQueryHistogram(type);
        }
    }

    public void recordQuery(String sql, long durationNanos, boolean success) {
        String type = categorize(sql);
        getQueryCounter(type).inc();
        double durationSeconds = durationNanos / 1_000_000_000.0;
        getQueryHistogram(type).observe(durationSeconds);
        if (!success) {
            errorsCounter.inc();
        }
    }

    public Counter getErrorsCounter() {
        return errorsCounter;
    }

    public Counter getQueryCounter(String type) {
        return queryCounters.computeIfAbsent(type, t ->
                registry.counter("koradb_queries_total", "Total SQL queries executed", "type", t));
    }

    public Histogram getQueryHistogram(String type) {
        return queryHistograms.computeIfAbsent(type, t ->
                registry.histogram("koradb_query_duration_seconds", "SQL query execution duration in seconds",
                        Histogram.DEFAULT_SECONDS_BUCKETS, "type", t));
    }

    public static String categorize(String sql) {
        if (sql == null || sql.isBlank()) {
            return "OTHER";
        }
        String trimmed = sql.trim().toUpperCase(Locale.ROOT);
        if (trimmed.startsWith("SELECT")) return "SELECT";
        if (trimmed.startsWith("INSERT")) return "INSERT";
        if (trimmed.startsWith("UPDATE")) return "UPDATE";
        if (trimmed.startsWith("DELETE")) return "DELETE";
        if (trimmed.startsWith("CREATE") || trimmed.startsWith("DROP") || trimmed.startsWith("ALTER") || trimmed.startsWith("USE")) return "DDL";
        if (trimmed.startsWith("BEGIN") || trimmed.startsWith("COMMIT") || trimmed.startsWith("ROLLBACK")) return "TXN";
        return "OTHER";
    }
}
