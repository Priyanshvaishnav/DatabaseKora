package dev.koradb.observability.metrics;

/**
 * Base interface for all measurable telemetry metrics.
 */
public interface Metric {

    /**
     * The unique metric name with associated labels.
     */
    MetricName name();

    /**
     * Human-readable description explaining what this metric measures.
     */
    String description();

    /**
     * The type of metric (COUNTER, GAUGE, HISTOGRAM).
     */
    MetricType type();
}
