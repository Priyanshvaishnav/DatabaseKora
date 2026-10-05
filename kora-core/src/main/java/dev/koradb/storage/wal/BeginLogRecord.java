package dev.koradb.storage.wal;

/**
 * Log record indicating the start of a transaction.
 */
public final class BeginLogRecord extends LogRecord {

    public BeginLogRecord(long lsn, long prevLsn, long txnId) {
        super(lsn, prevLsn, txnId, LogRecordType.BEGIN);
    }

    public BeginLogRecord(long prevLsn, long txnId) {
        this(0L, prevLsn, txnId);
    }

    @Override
    public LogRecord withLsn(long newLsn) {
        return new BeginLogRecord(newLsn, prevLsn, txnId);
    }
}
