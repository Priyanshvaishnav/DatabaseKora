package dev.koradb.replication.protocol;

import java.util.Objects;

/**
 * Heartbeat response sent from Replica to Primary.
 */
public record HeartbeatAckMessage(
        String replicaId,
        long lastReceivedLsn,
        long timestamp
) implements ReplicationMessage {

    public HeartbeatAckMessage {
        Objects.requireNonNull(replicaId, "replicaId must not be null");
    }

    @Override
    public ReplicationMessageType getType() {
        return ReplicationMessageType.HEARTBEAT_ACK;
    }
}
