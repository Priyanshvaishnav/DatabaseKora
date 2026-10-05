package dev.koradb.observability.metrics;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * A histogram metric for sampling observations (typically durations or sizes) into configurable buckets.
 * Also tracks cumulative sum and count, allowing calculation of quantiles (p50, p90, p99).
 */
public final class Histogram implements Metric {

    public static final double[] DEFAULT_SECONDS_BUCKETS = {
            0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0
    };

    private final MetricName name;
    private final String description;
    private final double[] upperBounds;
    private final LongAdder[] bucketCounts;
    private final LongAdder count = new LongAdder();
    private final DoubleAdder sum = new DoubleAdder();

    public Histogram(MetricName name, String description, double[] buckets) {
        this.name = Objects.requireNonNull(name, "MetricName must not be null");
        this.description = description == null ? "" : description;
        double[] sorted = buckets == null || buckets.length == 0
                ? DEFAULT_SECONDS_BUCKETS.clone()
                : buckets.clone();
        Arrays.sort(sorted);
        this.upperBounds = sorted;
        this.bucketCounts = new LongAdder[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            this.bucketCounts[i] = new LongAdder();
        }
    }

    public Histogram(MetricName name, String description) {
        this(name, description, DEFAULT_SECONDS_BUCKETS);
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
        return MetricType.HISTOGRAM;
    }

    /**
     * Records a new observation.
     *
     * @param value observation value (e.g. latency in seconds, or size in bytes)
     */
    public void observe(double value) {
        count.increment();
        sum.add(value);
        for (int i = 0; i < upperBounds.length; i++) {
            if (value <= upperBounds[i]) {
                bucketCounts[i].increment();
            }
        }
    }

    /**
     * Records the execution time of a runnable in seconds.
     */
    public void recordDuration(Runnable action) {
        long start = System.nanoTime();
        try {
            action.run();
        } finally {
            double durationSec = (System.nanoTime() - start) / 1_000_000_000.0;
            observe(durationSec);
        }
    }

    /**
     * Records the execution time of a callable in seconds, returning its result.
     */
    public <T> T recordDuration(Callable<T> callable) throws Exception {
        long start = System.nanoTime();
        try {
            return callable.call();
        } finally {
            double durationSec = (System.nanoTime() - start) / 1_000_000_000.0;
            observe(durationSec);
        }
    }

    public long getCount() {
        return count.sum();
    }

    public double getSum() {
        return sum.sum();
    }

    public double[] getUpperBounds() {
        return upperBounds.clone();
    }

    public long[] getBucketCounts() {
        long[] counts = new long[bucketCounts.length];
        for (int i = 0; i < bucketCounts.length; i++) {
            counts[i] = bucketCounts[i].sum();
        }
        return counts;
    }

    /**
     * Estimates a quantile (e.g. 0.50 for p50, 0.99 for p99) via linear interpolation across buckets.
     *
     * @param q target quantile between 0.0 and 1.0
     * @return estimated value, or 0.0 if no observations recorded
     */
    public double getQuantile(double q) {
        long total = count.sum();
        if (total == 0) return 0.0;
        if (q <= 0.0) return 0.0;
        if (q >= 1.0) return upperBounds[upperBounds.length - 1];

        double targetRank = q * total;
        long prevCount = 0;
        double lowerBound = 0.0;

        for (int i = 0; i < upperBounds.length; i++) {
            long currentCount = bucketCounts[i].sum();
            if (currentCount >= targetRank) {
                long bucketSpan = currentCount - prevCount;
                if (bucketSpan == 0) {
                    return upperBounds[i];
                }
                double fraction = (targetRank - prevCount) / (double) bucketSpan;
                return lowerBound + fraction * (upperBounds[i] - lowerBound);
            }
            prevCount = currentCount;
            lowerBound = upperBounds[i];
        }

        return upperBounds[upperBounds.length - 1];
    }

    /**
     * Resets all histogram counts and sums.
     */
    public void reset() {
        count.reset();
        sum.reset();
        for (LongAdder b : bucketCounts) {
            b.reset();
        }
    }

    @Override
    public String toString() {
        return String.format("%s{count=%d, sum=%.4f, p50=%.4f, p95=%.4f, p99=%.4f}",
                name, getCount(), getSum(), getQuantile(0.50), getQuantile(0.95), getQuantile(0.99));
    }
}
