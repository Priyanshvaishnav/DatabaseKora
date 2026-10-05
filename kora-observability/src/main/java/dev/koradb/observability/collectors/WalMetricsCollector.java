package dev.koradb.observability.collectors;

import dev.koradb.observability.metrics.Counter;
import dev.koradb.observability.metrics.Histogram;
import dev.koradb.observability.metrics.MetricsRegistry;
import dev.koradb.storage.wal.LogManager;

import java.util.Objects;

/**
 * Collects and exposes Write-Ahead Log (WAL) metrics to the {@link MetricsRegistry}.
 */
public final class WalMetricsCollector {

    private final MetricsRegistry registry;
    private final java.util.function.Supplier<LogManager> logManagerSupplier;
    private final Counter flushesCounter;
    private final Histogram flushDuration;
    private volatile LogManager lastBoundLm;

    public WalMetricsCollector(MetricsRegistry registry, LogManager logManager) {
        this(registry, () -> logManager);
    }

    public WalMetricsCollector(MetricsRegistry registry, java.util.function.Supplier<LogManager> logManagerSupplier) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.logManagerSupplier = Objects.requireNonNull(logManagerSupplier, "logManagerSupplier must not be null");

        this.flushesCounter = registry.counter("koradb_wal_flushes_total", "Total disk fsync flush operations on WAL");
        this.flushDuration = registry.histogram("koradb_wal_flush_duration_seconds", "Duration of WAL fsync operations in seconds",
                new double[]{0.0001, 0.0005, 0.001, 0.005, 0.01, 0.05, 0.1});

        registry.gauge("koradb_wal_bytes_total", "Total bytes written to the Write-Ahead Log",
                () -> {
                    LogManager lm = getActiveLogManager();
                    return lm == null ? 0.0 : Math.max(0, lm.getNextLsn() - LogManager.WAL_HEADER_SIZE);
                });

        registry.gauge("koradb_wal_flushed_lsn", "Most recent LSN flushed and fsynced to disk",
                () -> {
                    LogManager lm = getActiveLogManager();
                    return lm == null ? 0.0 : lm.getFlushedLsn();
                });

        getActiveLogManager(); // Attach listener eagerly if LogManager is already available
    }

    private LogManager getActiveLogManager() {
        LogManager current = logManagerSupplier.get();
        if (current != null && current != lastBoundLm) {
            synchronized (this) {
                if (current != lastBoundLm) {
                    current.addFlushListener(flushedLsn -> flushesCounter.inc());
                    lastBoundLm = current;
                }
            }
        }
        return current;
    }

    public Counter getFlushesCounter() {
        return flushesCounter;
    }

    public Histogram getFlushDuration() {
        return flushDuration;
    }
}
