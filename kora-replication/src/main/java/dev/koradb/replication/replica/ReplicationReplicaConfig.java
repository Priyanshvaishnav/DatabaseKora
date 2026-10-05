package dev.koradb.replication.replica;

import dev.koradb.replication.model.ReplicationMode;

import java.util.Objects;

/**
 * Configuration settings for a Replica node connecting to a Primary node.
 */
public record ReplicationReplicaConfig(
        String replicaId,
        String primaryHost,
        int primaryPort,
        ReplicationMode mode,
        long reconnectIntervalMs,
        boolean requestSnapshotOnStart
) {

    public ReplicationReplicaConfig {
        Objects.requireNonNull(replicaId, "replicaId must not be null");
        Objects.requireNonNull(primaryHost, "primaryHost must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
    }

    public static ReplicationReplicaConfig of(String replicaId, String primaryHost, int primaryPort) {
        return new ReplicationReplicaConfig(replicaId, primaryHost, primaryPort, ReplicationMode.ASYNC, 1000L, false);
    }

    public static ReplicationReplicaConfig of(String replicaId, String primaryHost, int primaryPort, ReplicationMode mode) {
        return new ReplicationReplicaConfig(replicaId, primaryHost, primaryPort, mode, 1000L, false);
    }
}
