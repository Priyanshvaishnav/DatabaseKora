package dev.koradb.storage.wal;

/**
 * Log record indicating successful transaction commit.
 */
public final class CommitLogRecord extends LogRecord {

    public CommitLogRecord(long lsn, long prevLsn, long txnId) {
        super(lsn, prevLsn, txnId, LogRecordType.COMMIT);
    }

    public CommitLogRecord(long prevLsn, long txnId) {
        this(0L, prevLsn, txnId);
    }

    @Override
    public LogRecord withLsn(long newLsn) {
        return new CommitLogRecord(newLsn, prevLsn, txnId);
    }
}
