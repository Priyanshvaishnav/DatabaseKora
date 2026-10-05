package dev.koradb.observability.collectors;

import dev.koradb.buffer.BufferPoolStats;
import dev.koradb.observability.metrics.MetricsRegistry;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Collects and exposes Buffer Pool metrics to the {@link MetricsRegistry}.
 */
public final class BufferPoolMetricsCollector {

    private final MetricsRegistry registry;
    private final Supplier<Collection<BufferPoolStats>> statsSupplier;

    public BufferPoolMetricsCollector(MetricsRegistry registry, Supplier<Collection<BufferPoolStats>> statsSupplier) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.statsSupplier = Objects.requireNonNull(statsSupplier, "statsSupplier must not be null");
        registerMetrics();
    }

    public static BufferPoolMetricsCollector forSingle(MetricsRegistry registry, BufferPoolStats singleStats) {
        Objects.requireNonNull(singleStats, "singleStats must not be null");
        return new BufferPoolMetricsCollector(registry, () -> List.of(singleStats));
    }

    private void registerMetrics() {
        registry.gauge("koradb_buffer_pool_hits_total", "Total page cache hits in buffer pool",
                () -> aggregate(BufferPoolStats::getHits));

        registry.gauge("koradb_buffer_pool_misses_total", "Total page cache misses in buffer pool",
                () -> aggregate(BufferPoolStats::getMisses));

        registry.gauge("koradb_buffer_pool_hit_ratio", "Buffer pool cache hit ratio (0.0 to 1.0)",
                this::calculateAggregateHitRatio);

        registry.gauge("koradb_buffer_pool_evictions_total", "Total frame evictions in buffer pool",
                () -> aggregate(BufferPoolStats::getEvictions));

        registry.gauge("koradb_buffer_pool_dirty_evictions_total", "Total dirty frame evictions requiring disk write",
                () -> aggregate(BufferPoolStats::getDirtyEvictions));
    }

    private double aggregate(java.util.function.ToLongFunction<BufferPoolStats> extractor) {
        Collection<BufferPoolStats> all = statsSupplier.get();
        if (all == null || all.isEmpty()) {
            return 0.0;
        }
        long sum = 0L;
        for (BufferPoolStats s : all) {
            if (s != null) {
                sum += extractor.applyAsLong(s);
            }
        }
        return sum;
    }

    private double calculateAggregateHitRatio() {
        Collection<BufferPoolStats> all = statsSupplier.get();
        if (all == null || all.isEmpty()) {
            return 0.0;
        }
        long totalHits = 0L;
        long totalMisses = 0L;
        for (BufferPoolStats s : all) {
            if (s != null) {
                totalHits += s.getHits();
                totalMisses += s.getMisses();
            }
        }
        long totalRequests = totalHits + totalMisses;
        return totalRequests == 0L ? 0.0 : (double) totalHits / totalRequests;
    }
}
