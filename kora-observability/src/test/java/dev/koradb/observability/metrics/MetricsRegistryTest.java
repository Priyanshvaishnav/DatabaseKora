package dev.koradb.observability.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricsRegistryTest {

    private MetricsRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MetricsRegistry();
    }

    @Test
    @DisplayName("Counter increments and resets properly")
    void testCounterOperations() {
        Counter c = registry.counter("koradb_queries_total", "Total queries executed", "type", "select");
        assertThat(c.get()).isEqualTo(0.0);

        c.inc();
        assertThat(c.get()).isEqualTo(1.0);

        c.inc(5.5);
        assertThat(c.get()).isEqualTo(6.5);

        assertThatThrownBy(() -> c.inc(-1.0))
                .isInstanceOf(IllegalArgumentException.class);

        // Fetching existing metric returns the same instance
        Counter cSame = registry.counter("koradb_queries_total", "Different desc", "type", "select");
        assertThat(cSame).isSameAs(c);
        assertThat(cSame.get()).isEqualTo(6.5);

        c.reset();
        assertThat(c.get()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Settable and supplier-backed Gauges work correctly")
    void testGaugeOperations() {
        Gauge settable = registry.gauge("koradb_active_connections", "Active client connections");
        settable.set(5.0);
        assertThat(settable.get()).isEqualTo(5.0);
        settable.inc();
        assertThat(settable.get()).isEqualTo(6.0);
        settable.dec();
        assertThat(settable.get()).isEqualTo(5.0);

        AtomicInteger value = new AtomicInteger(42);
        Gauge dynamic = registry.gauge("koradb_dynamic_val", "Dynamic gauge", value::get);
        assertThat(dynamic.get()).isEqualTo(42.0);
        value.set(99);
        assertThat(dynamic.get()).isEqualTo(99.0);

        assertThatThrownBy(() -> dynamic.set(10.0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("MetricName sorts labels naturally for consistent hash equality")
    void testMetricNameLabelSorting() {
        MetricName m1 = MetricName.ofKeyValues("test", "b", "2", "a", "1");
        MetricName m2 = MetricName.ofKeyValues("test", "a", "1", "b", "2");

        assertThat(m1).isEqualTo(m2);
        assertThat(m1.hashCode()).isEqualTo(m2.hashCode());
        assertThat(m1.toString()).isEqualTo("test{a=\"1\",b=\"2\"}");
    }
}
