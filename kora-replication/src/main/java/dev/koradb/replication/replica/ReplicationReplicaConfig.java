package dev.koradb.replication.replica;

import dev.koradb.replication.model.ReplicationMode;

import java.util.Objects;

/**
 * Configuration settings for a Replica node connecting to a Primary node.
 *
 * @param authToken shared secret sent in the handshake, for primaries started with a password
 */
public record ReplicationReplicaConfig(
        String replicaId,
        String primaryHost,
        int primaryPort,
        ReplicationMode mode,
        long reconnectIntervalMs,
        boolean requestSnapshotOnStart,
        String authToken
) {

    public ReplicationReplicaConfig {
        Objects.requireNonNull(replicaId, "replicaId must not be null");
        Objects.requireNonNull(primaryHost, "primaryHost must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        if (authToken == null) authToken = "";
    }

    public ReplicationReplicaConfig(String replicaId, String primaryHost, int primaryPort, ReplicationMode mode,
                                    long reconnectIntervalMs, boolean requestSnapshotOnStart) {
        this(replicaId, primaryHost, primaryPort, mode, reconnectIntervalMs, requestSnapshotOnStart, "");
    }

    public static ReplicationReplicaConfig of(String replicaId, String primaryHost, int primaryPort) {
        return new ReplicationReplicaConfig(replicaId, primaryHost, primaryPort, ReplicationMode.ASYNC, 1000L, false);
    }

    public static ReplicationReplicaConfig of(String replicaId, String primaryHost, int primaryPort, ReplicationMode mode) {
        return new ReplicationReplicaConfig(replicaId, primaryHost, primaryPort, mode, 1000L, false);
    }

    public ReplicationReplicaConfig withAuthToken(String authToken) {
        return new ReplicationReplicaConfig(replicaId, primaryHost, primaryPort, mode, reconnectIntervalMs,
                requestSnapshotOnStart, authToken);
    }

    @Override
    public String toString() {
        return "ReplicationReplicaConfig[replicaId=" + replicaId + ", primary=" + primaryHost + ":" + primaryPort
                + ", mode=" + mode + ", authToken=" + (authToken.isEmpty() ? "<none>" : "****") + "]";
    }
}
