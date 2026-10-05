package dev.koradb.replication.protocol;

import java.util.Objects;

/**
 * Request sent by a Replica to Primary requesting an initial snapshot of database files.
 */
public record SnapshotRequest(
        String replicaId,
        String databaseName
) implements ReplicationMessage {

    public SnapshotRequest {
        Objects.requireNonNull(replicaId, "replicaId must not be null");
        Objects.requireNonNull(databaseName, "databaseName must not be null");
    }

    @Override
    public ReplicationMessageType getType() {
        return ReplicationMessageType.SNAPSHOT_REQ;
    }
}
