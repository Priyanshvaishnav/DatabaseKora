package dev.koradb.replication.protocol;

import dev.koradb.replication.model.ReplicationMode;
import java.util.Objects;

/**
 * Handshake request sent by a Replica connecting to a Primary node.
 */
public record HandshakeRequest(
        String replicaId,
        long lastFlushedLsn,
        ReplicationMode mode
) implements ReplicationMessage {

    public HandshakeRequest {
        Objects.requireNonNull(replicaId, "replicaId must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
    }

    @Override
    public ReplicationMessageType getType() {
        return ReplicationMessageType.HANDSHAKE_REQ;
    }
}
