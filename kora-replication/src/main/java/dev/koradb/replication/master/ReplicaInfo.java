package dev.koradb.replication.master;

import dev.koradb.replication.model.ReplicationMode;

import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metadata and dynamic replication progress metrics for a connected Replica.
 */
public final class ReplicaInfo {

    private final String replicaId;
    private final SocketAddress remoteAddress;
    private final ReplicationMode mode;
    private final long connectedAt;
    private final AtomicLong lastAckFlushedLsn = new AtomicLong(0L);
    private final AtomicLong lastAckAppliedLsn = new AtomicLong(0L);
    private final AtomicLong lastHeartbeatTimestamp = new AtomicLong(System.currentTimeMillis());

    public ReplicaInfo(String replicaId, SocketAddress remoteAddress, ReplicationMode mode, long connectedAt) {
        this.replicaId = Objects.requireNonNull(replicaId, "replicaId must not be null");
        this.remoteAddress = remoteAddress;
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.connectedAt = connectedAt;
    }

    public String getReplicaId() {
        return replicaId;
    }

    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public ReplicationMode getMode() {
        return mode;
    }

    public long getConnectedAt() {
        return connectedAt;
    }

    public long getLastAckFlushedLsn() {
        return lastAckFlushedLsn.get();
    }

    public void setLastAckFlushedLsn(long lsn) {
        lastAckFlushedLsn.set(lsn);
    }

    public long getLastAckAppliedLsn() {
        return lastAckAppliedLsn.get();
    }

    public void setLastAckAppliedLsn(long lsn) {
        lastAckAppliedLsn.set(lsn);
    }

    public long getLastHeartbeatTimestamp() {
        return lastHeartbeatTimestamp.get();
    }

    public void setLastHeartbeatTimestamp(long timestamp) {
        lastHeartbeatTimestamp.set(timestamp);
    }

    public long computeLagBytes(long currentPrimaryLsn) {
        return Math.max(0L, currentPrimaryLsn - lastAckAppliedLsn.get());
    }

    public long computeLagMs(long currentTimestamp) {
        return Math.max(0L, currentTimestamp - lastHeartbeatTimestamp.get());
    }

    @Override
    public String toString() {
        return "ReplicaInfo{id='%s', address=%s, mode=%s, flushedLsn=%d, appliedLsn=%d}"
                .formatted(replicaId, remoteAddress, mode, lastAckFlushedLsn.get(), lastAckAppliedLsn.get());
    }
}
