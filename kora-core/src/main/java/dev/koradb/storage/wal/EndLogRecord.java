package dev.koradb.storage.wal;

/**
 * Log record indicating that a transaction has completely finished and all
 * of its undo operations (if aborted) or commit tasks have completed.
 */
public final class EndLogRecord extends LogRecord {

    public EndLogRecord(long lsn, long prevLsn, long txnId) {
        super(lsn, prevLsn, txnId, LogRecordType.END);
    }

    public EndLogRecord(long prevLsn, long txnId) {
        this(0L, prevLsn, txnId);
    }

    @Override
    public LogRecord withLsn(long newLsn) {
        return new EndLogRecord(newLsn, getPrevLsn(), getTxnId());
    }

    @Override
    public String toString() {
        return "EndLogRecord{" +
                "lsn=" + getLsn() +
                ", prevLsn=" + getPrevLsn() +
                ", txnId=" + getTxnId() +
                '}';
    }
}
