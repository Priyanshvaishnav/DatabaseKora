package dev.koradb.observability;

import dev.koradb.buffer.BufferPoolStats;
import dev.koradb.observability.collectors.*;
import dev.koradb.observability.exporter.MetricsExporterConfig;
import dev.koradb.observability.exporter.MetricsHttpServer;
import dev.koradb.observability.metrics.MetricsRegistry;
import dev.koradb.observability.tracing.InMemoryTraceExporter;
import dev.koradb.observability.tracing.Tracer;
import dev.koradb.storage.wal.LogManager;
import dev.koradb.transaction.TransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Central coordinator for the KoraDB Observability subsystem.
 * Manages metrics, distributed tracing, subsystem telemetry collectors, and the Prometheus HTTP exporter.
 */
public final class ObservabilityEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityEngine.class);
    private static final ObservabilityEngine INSTANCE = new ObservabilityEngine();

    private final MetricsRegistry metricsRegistry;
    private final Tracer tracer;
    private final InMemoryTraceExporter traceExporter;
    private final QueryMetricsCollector queryCollector;
    private final ReplicationMetricsCollector replicationCollector;

    private volatile BufferPoolMetricsCollector bufferPoolCollector;
    private volatile TransactionMetricsCollector transactionCollector;
    private volatile WalMetricsCollector walCollector;
    private volatile MetricsHttpServer httpServer;

    public ObservabilityEngine() {
        this.metricsRegistry = new MetricsRegistry();
        this.tracer = Tracer.of("koradb");
        this.traceExporter = new InMemoryTraceExporter();
        this.tracer.addExporter(traceExporter);
        this.queryCollector = new QueryMetricsCollector(metricsRegistry);
        this.replicationCollector = new ReplicationMetricsCollector(metricsRegistry);
        dev.koradb.KoraDB.setMetricsStatusSupplier(this::formatMetricsAsQueryResult);
    }

    public dev.koradb.execution.QueryResult formatMetricsAsQueryResult() {
        List<String> cols = List.of("name", "type", "value");
        List<dev.koradb.catalog.Row> rows = new java.util.ArrayList<>();
        for (dev.koradb.observability.metrics.Metric m : metricsRegistry.allMetrics()) {
            String val = switch (m) {
                case dev.koradb.observability.metrics.Counter c -> String.valueOf(c.get());
                case dev.koradb.observability.metrics.Gauge g -> String.valueOf(g.get());
                case dev.koradb.observability.metrics.Histogram h ->
                        String.format(java.util.Locale.ROOT, "count=%d, sum=%.4f, p95=%.4f", h.getCount(), h.getSum(), h.getQuantile(0.95));
                default -> "";
            };
            rows.add(new dev.koradb.catalog.Row(java.util.Map.of(
                    "name", m.name().toString(),
                    "type", m.type().name(),
                    "value", val
            )));
        }
        return dev.koradb.execution.QueryResult.select(rows, cols);
    }

    public static ObservabilityEngine getInstance() {
        return INSTANCE;
    }

    public MetricsRegistry getMetricsRegistry() {
        return metricsRegistry;
    }

    public Tracer getTracer() {
        return tracer;
    }

    public InMemoryTraceExporter getTraceExporter() {
        return traceExporter;
    }

    public QueryMetricsCollector getQueryCollector() {
        return queryCollector;
    }

    public ReplicationMetricsCollector getReplicationCollector() {
        return replicationCollector;
    }

    public BufferPoolMetricsCollector getBufferPoolCollector() {
        return bufferPoolCollector;
    }

    public TransactionMetricsCollector getTransactionCollector() {
        return transactionCollector;
    }

    public WalMetricsCollector getWalCollector() {
        return walCollector;
    }

    public MetricsHttpServer getHttpServer() {
        return httpServer;
    }

    public synchronized BufferPoolMetricsCollector bindBufferPool(BufferPoolStats stats) {
        this.bufferPoolCollector = BufferPoolMetricsCollector.forSingle(metricsRegistry, stats);
        return this.bufferPoolCollector;
    }

    public synchronized BufferPoolMetricsCollector bindBufferPools(Supplier<Collection<BufferPoolStats>> statsSupplier) {
        this.bufferPoolCollector = new BufferPoolMetricsCollector(metricsRegistry, statsSupplier);
        return this.bufferPoolCollector;
    }

    public synchronized TransactionMetricsCollector bindTransactionManager(TransactionManager tm) {
        this.transactionCollector = new TransactionMetricsCollector(metricsRegistry, tm);
        return this.transactionCollector;
    }

    public synchronized WalMetricsCollector bindLogManager(LogManager lm) {
        this.walCollector = new WalMetricsCollector(metricsRegistry, lm);
        return this.walCollector;
    }

    public synchronized WalMetricsCollector bindLogManager(Supplier<LogManager> supplier) {
        this.walCollector = new WalMetricsCollector(metricsRegistry, supplier);
        return this.walCollector;
    }

    public synchronized MetricsHttpServer startHttpExporter(int port) throws IOException {
        return startHttpExporter(MetricsExporterConfig.onPort(port));
    }

    public synchronized MetricsHttpServer startHttpExporter(MetricsExporterConfig config) throws IOException {
        Objects.requireNonNull(config, "config must not be null");
        if (httpServer != null && httpServer.isRunning()) {
            return httpServer;
        }
        httpServer = new MetricsHttpServer(config, metricsRegistry);
        httpServer.start();
        return httpServer;
    }

    public synchronized void stopHttpExporter() {
        if (httpServer != null) {
            httpServer.close();
            httpServer = null;
        }
    }

    public void recordQuery(String sql, long durationNanos, boolean success) {
        queryCollector.recordQuery(sql, durationNanos, success);
    }

    @Override
    public void close() {
        stopHttpExporter();
    }
}
