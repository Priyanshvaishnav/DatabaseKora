package dev.koradb.replication.model;

import dev.koradb.replication.master.ReplicaInfo;

import java.util.List;

/**
 * Snapshot of replication health, topology, and metrics for a node.
 */
public record ReplicationStatus(
        NodeRole role,
        ReplicationMode mode,
        long currentLsn,
        long appliedLsn,
        int connectedReplicasCount,
        List<ReplicaInfo> replicas,
        long maxLagBytes,
        long maxLagMs,
        boolean isHealthy
) {

    public ReplicationStatus {
        replicas = replicas != null ? List.copyOf(replicas) : List.of();
    }

    public static ReplicationStatus primary(ReplicationMode mode, long currentLsn, List<ReplicaInfo> replicas) {
        long maxLagBytes = 0L;
        long maxLagMs = 0L;
        long now = System.currentTimeMillis();
        for (ReplicaInfo r : replicas) {
            maxLagBytes = Math.max(maxLagBytes, r.computeLagBytes(currentLsn));
            maxLagMs = Math.max(maxLagMs, r.computeLagMs(now));
        }

        return new ReplicationStatus(
                NodeRole.PRIMARY,
                mode,
                currentLsn,
                currentLsn,
                replicas.size(),
                replicas,
                maxLagBytes,
                maxLagMs,
                true
        );
    }

    public static ReplicationStatus replica(ReplicationMode mode, long receivedLsn, long appliedLsn, long lagBytes, long lagMs, boolean connected) {
        return new ReplicationStatus(
                NodeRole.REPLICA,
                mode,
                receivedLsn,
                appliedLsn,
                0,
                List.of(),
                lagBytes,
                lagMs,
                connected
        );
    }
}
