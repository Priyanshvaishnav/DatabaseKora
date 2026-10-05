package dev.koradb.observability.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HistogramTest {

    @Test
    @DisplayName("Histogram tracks observations, count, sum, and quantile interpolation")
    void testHistogramObservations() {
        Histogram h = new Histogram(
                MetricName.of("koradb_query_duration_seconds"),
                "Query duration",
                new double[]{0.01, 0.05, 0.1, 0.5, 1.0}
        );

        assertThat(h.getCount()).isEqualTo(0);
        assertThat(h.getSum()).isEqualTo(0.0);

        h.observe(0.005); // bucket 0 (<= 0.01)
        h.observe(0.020); // bucket 1 (<= 0.05)
        h.observe(0.080); // bucket 2 (<= 0.1)
        h.observe(0.400); // bucket 3 (<= 0.5)

        assertThat(h.getCount()).isEqualTo(4);
        assertThat(h.getSum()).isCloseTo(0.505, within(0.001));

        long[] bucketCounts = h.getBucketCounts();
        // bucket 0 (<= 0.01): 1
        assertThat(bucketCounts[0]).isEqualTo(1);
        // bucket 1 (<= 0.05): 2 (0.005, 0.020)
        assertThat(bucketCounts[1]).isEqualTo(2);
        // bucket 2 (<= 0.1): 3
        assertThat(bucketCounts[2]).isEqualTo(3);
        // bucket 3 (<= 0.5): 4
        assertThat(bucketCounts[3]).isEqualTo(4);
        // bucket 4 (<= 1.0): 4
        assertThat(bucketCounts[4]).isEqualTo(4);

        // Quantiles
        double p50 = h.getQuantile(0.50);
        assertThat(p50).isBetween(0.01, 0.06);

        double p99 = h.getQuantile(0.99);
        assertThat(p99).isBetween(0.3, 0.5);
    }

    @Test
    @DisplayName("Histogram recordDuration executes action and measures elapsed time")
    void testRecordDuration() {
        Histogram h = new Histogram(MetricName.of("test_duration"), "test");
        h.recordDuration(() -> {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {}
        });

        assertThat(h.getCount()).isEqualTo(1);
        assertThat(h.getSum()).isGreaterThan(0.005);
    }
}
