package dev.koradb.observability.metrics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.DoubleSupplier;

/**
 * Thread-safe central registry managing all metrics in KoraDB.
 */
public final class MetricsRegistry {

    private final ConcurrentMap<MetricName, Metric> metrics = new ConcurrentHashMap<>();

    public MetricsRegistry() {
    }

    /**
     * Registers an existing metric into the registry.
     */
    public <T extends Metric> T register(T metric) {
        Objects.requireNonNull(metric, "Metric must not be null");
        Metric existing = metrics.putIfAbsent(metric.name(), metric);
        if (existing != null) {
            @SuppressWarnings("unchecked")
            T cast = (T) existing;
            return cast;
        }
        return metric;
    }

    /**
     * Retrieves or creates a Counter metric.
     */
    public Counter counter(String name, String description, String... labelKeyValues) {
        MetricName metricName = MetricName.ofKeyValues(name, labelKeyValues);
        return (Counter) metrics.computeIfAbsent(metricName, n -> new Counter(n, description));
    }

    /**
     * Retrieves or creates a Counter metric with pre-built MetricName.
     */
    public Counter counter(MetricName name, String description) {
        return (Counter) metrics.computeIfAbsent(name, n -> new Counter(n, description));
    }

    /**
     * Retrieves or creates a settable Gauge metric.
     */
    public Gauge gauge(String name, String description, String... labelKeyValues) {
        MetricName metricName = MetricName.ofKeyValues(name, labelKeyValues);
        return (Gauge) metrics.computeIfAbsent(metricName, n -> new Gauge(n, description));
    }

    /**
     * Retrieves or creates a supplier-backed Gauge metric.
     */
    public Gauge gauge(String name, String description, DoubleSupplier supplier, String... labelKeyValues) {
        MetricName metricName = MetricName.ofKeyValues(name, labelKeyValues);
        return (Gauge) metrics.computeIfAbsent(metricName, n -> new Gauge(n, description, supplier));
    }

    /**
     * Retrieves or creates a supplier-backed Gauge metric with pre-built MetricName.
     */
    public Gauge gauge(MetricName name, String description, DoubleSupplier supplier) {
        return (Gauge) metrics.computeIfAbsent(name, n -> new Gauge(n, description, supplier));
    }

    /**
     * Retrieves or creates a Histogram metric with default duration buckets.
     */
    public Histogram histogram(String name, String description, String... labelKeyValues) {
        return histogram(name, description, Histogram.DEFAULT_SECONDS_BUCKETS, labelKeyValues);
    }

    /**
     * Retrieves or creates a Histogram metric with custom buckets.
     */
    public Histogram histogram(String name, String description, double[] buckets, String... labelKeyValues) {
        MetricName metricName = MetricName.ofKeyValues(name, labelKeyValues);
        return (Histogram) metrics.computeIfAbsent(metricName, n -> new Histogram(n, description, buckets));
    }

    /**
     * Finds a metric by name.
     */
    public Optional<Metric> get(MetricName name) {
        return Optional.ofNullable(metrics.get(name));
    }

    /**
     * Returns an unmodifiable snapshot of all registered metrics sorted by name.
     */
    public List<Metric> allMetrics() {
        List<Metric> list = new ArrayList<>(metrics.values());
        list.sort(Comparator.comparing(Metric::name));
        return Collections.unmodifiableList(list);
    }

    /**
     * Clears all metrics from this registry.
     */
    public void clear() {
        metrics.clear();
    }
}
