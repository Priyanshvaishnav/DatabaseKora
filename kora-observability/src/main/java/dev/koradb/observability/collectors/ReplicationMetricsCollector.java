package dev.koradb.observability.collectors;

import dev.koradb.observability.metrics.MetricsRegistry;

import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Collects and exposes Primary and Replica replication telemetry to {@link MetricsRegistry}.
 */
public final class ReplicationMetricsCollector {

    private final MetricsRegistry registry;

    public ReplicationMetricsCollector(MetricsRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * Binds Primary node replication metrics.
     */
    public void bindMaster(DoubleSupplier connectedReplicasSupplier) {
        registry.gauge("koradb_replication_connected_replicas", "Number of active replica connections on Primary",
                connectedReplicasSupplier);
    }

    /**
     * Binds Replica node replication metrics.
     */
    public void bindReplica(DoubleSupplier lagBytesSupplier,
                            DoubleSupplier lagLatencyMsSupplier,
                            DoubleSupplier isConnectedSupplier) {
        registry.gauge("koradb_replication_lag_bytes", "Replication lag in bytes behind Primary WAL head",
                lagBytesSupplier);
        registry.gauge("koradb_replication_lag_milliseconds", "Estimated replication lag in milliseconds",
                lagLatencyMsSupplier);
        registry.gauge("koradb_replication_is_connected", "Replica connection status to Primary (1.0 = connected, 0.0 = disconnected)",
                isConnectedSupplier);
    }
}
