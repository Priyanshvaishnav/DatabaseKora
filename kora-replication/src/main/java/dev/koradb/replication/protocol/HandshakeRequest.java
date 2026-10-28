package dev.koradb.replication.protocol;

import dev.koradb.replication.model.ReplicationMode;
import java.util.Objects;

/**
 * Handshake request sent by a Replica connecting to a Primary node.
 *
 * @param authToken shared secret the primary requires when it was started with a password
 *                  (empty if none); encoded after {@code mode}, so older peers ignore it
 */
public record HandshakeRequest(
        String replicaId,
        long lastFlushedLsn,
        ReplicationMode mode,
        String authToken
) implements ReplicationMessage {

    public HandshakeRequest {
        Objects.requireNonNull(replicaId, "replicaId must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        if (authToken == null) authToken = "";
    }

    public HandshakeRequest(String replicaId, long lastFlushedLsn, ReplicationMode mode) {
        this(replicaId, lastFlushedLsn, mode, "");
    }

    @Override
    public ReplicationMessageType getType() {
        return ReplicationMessageType.HANDSHAKE_REQ;
    }

    @Override
    public String toString() {
        return "HandshakeRequest[replicaId=" + replicaId + ", lastFlushedLsn=" + lastFlushedLsn + ", mode=" + mode
                + ", authToken=" + (authToken.isEmpty() ? "" : "****") + "]";
    }
}
