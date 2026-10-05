package dev.koradb.observability.metrics;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

/**
 * A gauge metric that represents an instantaneous value that can fluctuate over time.
 * Can be either explicitly set, or polled on-demand via a {@link DoubleSupplier}.
 */
public final class Gauge implements Metric {

    private final MetricName name;
    private final String description;
    private final DoubleSupplier supplier;
    private final AtomicLong bitHolder; // Used if no external supplier provided

    public Gauge(MetricName name, String description, DoubleSupplier supplier) {
        this.name = Objects.requireNonNull(name, "MetricName must not be null");
        this.description = description == null ? "" : description;
        this.supplier = supplier;
        this.bitHolder = supplier == null ? new AtomicLong(Double.doubleToRawLongBits(0.0)) : null;
    }

    public Gauge(MetricName name, String description) {
        this(name, description, null);
    }

    public static Gauge of(MetricName name, String description, DoubleSupplier supplier) {
        return new Gauge(name, description, supplier);
    }

    public static Gauge of(MetricName name, String description) {
        return new Gauge(name, description);
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
        return MetricType.GAUGE;
    }

    /**
     * Sets the current value of this gauge. Only supported for settable gauges.
     */
    public void set(double val) {
        if (bitHolder == null) {
            throw new UnsupportedOperationException("Cannot set value on a supplier-backed gauge");
        }
        bitHolder.set(Double.doubleToRawLongBits(val));
    }

    /**
     * Increments the gauge value by 1.0.
     */
    public void inc() {
        add(1.0);
    }

    /**
     * Decrements the gauge value by 1.0.
     */
    public void dec() {
        add(-1.0);
    }

    /**
     * Adds an amount to the gauge value.
     */
    public void add(double delta) {
        if (bitHolder == null) {
            throw new UnsupportedOperationException("Cannot add value on a supplier-backed gauge");
        }
        bitHolder.updateAndGet(curr -> {
            double d = Double.longBitsToDouble(curr);
            return Double.doubleToRawLongBits(d + delta);
        });
    }

    /**
     * Returns the current value of the gauge.
     */
    public double get() {
        if (supplier != null) {
            try {
                return supplier.getAsDouble();
            } catch (Exception e) {
                return Double.NaN;
            }
        }
        return Double.longBitsToDouble(bitHolder.get());
    }

    @Override
    public String toString() {
        return name + "=" + get();
    }
}
