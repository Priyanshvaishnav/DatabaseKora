package dev.koradb.storage.wal;

/**
 * Log record indicating the start of a checkpoint.
 */
public final class CheckpointBeginRecord extends LogRecord {

    private final long timestamp;

    public CheckpointBeginRecord(long lsn, long timestamp) {
        super(lsn, 0L, 0L, LogRecordType.CHECKPOINT_BEGIN);
        this.timestamp = timestamp;
    }

    public CheckpointBeginRecord(long timestamp) {
        this(0L, timestamp);
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public LogRecord withLsn(long newLsn) {
        return new CheckpointBeginRecord(newLsn, timestamp);
    }
}
