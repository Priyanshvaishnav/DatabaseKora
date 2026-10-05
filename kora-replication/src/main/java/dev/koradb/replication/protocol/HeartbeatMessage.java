package dev.koradb.replication.protocol;

/**
 * Periodic heartbeat message sent from Primary to Replica.
 */
public record HeartbeatMessage(
        long primaryLsn,
        long timestamp
) implements ReplicationMessage {

    @Override
    public ReplicationMessageType getType() {
        return ReplicationMessageType.HEARTBEAT;
    }
}
