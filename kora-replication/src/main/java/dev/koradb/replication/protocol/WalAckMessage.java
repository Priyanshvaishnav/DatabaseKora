package dev.koradb.replication.protocol;

import java.util.Objects;

/**
 * Acknowledgment sent from Replica to Primary reporting flushed LSN and applied LSN.
 */
public record WalAckMessage(
        String replicaId,
        long flushedLsn,
        long appliedLsn,
        long timestamp
) implements ReplicationMessage {

    public WalAckMessage {
        Objects.requireNonNull(replicaId, "replicaId must not be null");
    }

    @Override
    public ReplicationMessageType getType() {
        return ReplicationMessageType.WAL_ACK;
    }
}
