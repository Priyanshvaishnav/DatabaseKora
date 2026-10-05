package dev.koradb.replication.protocol;

import java.util.Map;
import java.util.Objects;

/**
 * Snapshot payload response sent by Primary to Replica containing table files, catalog.meta, and snapshot LSN.
 */
public record SnapshotResponse(
        String databaseName,
        long snapshotLsn,
        Map<String, byte[]> files
) implements ReplicationMessage {

    public SnapshotResponse {
        Objects.requireNonNull(databaseName, "databaseName must not be null");
        Objects.requireNonNull(files, "files must not be null");
        files = Map.copyOf(files);
    }

    @Override
    public ReplicationMessageType getType() {
        return ReplicationMessageType.SNAPSHOT_RESP;
    }
}
