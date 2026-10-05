package dev.koradb.replication.protocol;

import java.util.Objects;

/**
 * Handshake response sent by Primary back to Replica.
 */
public record HandshakeResponse(
        String primaryId,
        long currentLsn,
        int walVersion,
        boolean accepted,
        String message
) implements ReplicationMessage {

    public HandshakeResponse {
        Objects.requireNonNull(primaryId, "primaryId must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }

    @Override
    public ReplicationMessageType getType() {
        return ReplicationMessageType.HANDSHAKE_RESP;
    }
}
