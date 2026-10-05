package dev.koradb.replication.protocol;

import dev.koradb.storage.wal.LogRecord;
import java.util.List;
import java.util.Objects;

/**
 * Message streaming a batch of WAL log records from Primary to Replica.
 */
public record WalDataMessage(
        long startLsn,
        long endLsn,
        List<LogRecord> records,
        long timestamp
) implements ReplicationMessage {

    public WalDataMessage {
        Objects.requireNonNull(records, "records must not be null");
        records = List.copyOf(records);
    }

    @Override
    public ReplicationMessageType getType() {
        return ReplicationMessageType.WAL_DATA;
    }
}
