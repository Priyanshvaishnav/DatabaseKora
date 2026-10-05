package dev.koradb.observability.metrics;

import java.util.Objects;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * A monotonically increasing counter metric.
 * Suitable for measuring cumulative values like queries served, transactions committed, or bytes read.
 */
public final class Counter implements Metric {

    private final MetricName name;
    private final String description;
    private final DoubleAdder value = new DoubleAdder();

    public Counter(MetricName name, String description) {
        this.name = Objects.requireNonNull(name, "MetricName must not be null");
        this.description = description == null ? "" : description;
    }

    @Override
    public MetricName name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public MetricType type() {
        return MetricType.COUNTER;
    }

    /**
     * Increments the counter by 1.0.
     */
    public void inc() {
        inc(1.0);
    }

    /**
     * Increments the counter by the specified non-negative amount.
     *
     * @param amount amount to add; must be &gt;= 0.0
     */
    public void inc(double amount) {
        if (amount < 0.0) {
            throw new IllegalArgumentException("Counter cannot be incremented by a negative value: " + amount);
        }
        value.add(amount);
    }

    /**
     * Returns the current total value of the counter.
     */
    public double get() {
        return value.sum();
    }

    /**
     * Resets the counter to 0.0.
     */
    public void reset() {
        value.reset();
    }

    @Override
    public String toString() {
        return name + "=" + get();
    }
}
