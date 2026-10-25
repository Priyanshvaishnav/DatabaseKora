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
        this(registry, logManagerSupplier, null);
    }

    /**
     * Aggregates over every open database's WAL (a server has one WAL per database):
     * bytes are summed, the flushed LSN is the maximum, and flushes of every WAL are counted.
     */
    public static WalMetricsCollector forAll(MetricsRegistry registry,
                                             java.util.function.Supplier<java.util.Collection<LogManager>> all) {
        return new WalMetricsCollector(registry, () -> null, all);
    }

    private final java.util.function.Supplier<java.util.Collection<LogManager>> allSupplier;
    private final java.util.Set<LogManager> boundAll = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private java.util.Collection<LogManager> activeLogManagers() {
        if (allSupplier == null) {
            LogManager lm = getActiveLogManager();
            return lm == null ? java.util.List.of() : java.util.List.of(lm);
        }
        java.util.Collection<LogManager> lms = allSupplier.get();
        synchronized (boundAll) {
            for (LogManager lm : lms) {
                if (boundAll.add(lm)) lm.addFlushListener(flushedLsn -> flushesCounter.inc());
            }
        }
        return lms;
    }

    private WalMetricsCollector(MetricsRegistry registry, java.util.function.Supplier<LogManager> logManagerSupplier,
                                java.util.function.Supplier<java.util.Collection<LogManager>> allSupplier) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.logManagerSupplier = Objects.requireNonNull(logManagerSupplier, "logManagerSupplier must not be null");
        this.allSupplier = allSupplier;

        this.flushesCounter = registry.counter("koradb_wal_flushes_total", "Total disk fsync flush operations on WAL");
        this.flushDuration = registry.histogram("koradb_wal_flush_duration_seconds", "Duration of WAL fsync operations in seconds",
                new double[]{0.0001, 0.0005, 0.001, 0.005, 0.01, 0.05, 0.1});

        registry.gauge("koradb_wal_bytes_total", "Total bytes written to the Write-Ahead Log",
                () -> activeLogManagers().stream()
                        .mapToDouble(lm -> Math.max(0, lm.getNextLsn() - LogManager.WAL_HEADER_SIZE)).sum());

        registry.gauge("koradb_wal_flushed_lsn", "Most recent LSN flushed and fsynced to disk",
                () -> activeLogManagers().stream().mapToDouble(LogManager::getFlushedLsn).max().orElse(0.0));

        activeLogManagers(); // Attach listener eagerly if LogManager is already available
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
