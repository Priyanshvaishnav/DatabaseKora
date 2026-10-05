package dev.koradb.replication.protocol;

/**
 * Base sealed interface for all KoraDB replication wire protocol messages.
 */
public sealed interface ReplicationMessage permits
        HandshakeRequest,
        HandshakeResponse,
        WalDataMessage,
        WalAckMessage,
        HeartbeatMessage,
        HeartbeatAckMessage,
        SnapshotRequest,
        SnapshotResponse {

    ReplicationMessageType getType();
}
