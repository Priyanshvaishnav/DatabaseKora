package dev.koradb.storage.wal;

/**
 * Log record indicating transaction abort/rollback.
 */
public final class AbortLogRecord extends LogRecord {

    public AbortLogRecord(long lsn, long prevLsn, long txnId) {
        super(lsn, prevLsn, txnId, LogRecordType.ABORT);
    }

    public AbortLogRecord(long prevLsn, long txnId) {
        this(0L, prevLsn, txnId);
    }

    @Override
    public LogRecord withLsn(long newLsn) {
        return new AbortLogRecord(newLsn, prevLsn, txnId);
    }
}
