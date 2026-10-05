package dev.koradb.storage.wal;

import java.util.Objects;

/**
 * Base abstract class for all Write-Ahead Log records.
 *
 * <p>Every log record contains:
 * <ul>
 *   <li>{@code lsn}: Log Sequence Number (monotonically increasing byte offset in the WAL).</li>
 *   <li>{@code prevLsn}: Previous LSN written by this transaction (forming a backward chain for Undo).</li>
 *   <li>{@code txnId}: Transaction ID that wrote this record (or 0 for non-transactional system records).</li>
 *   <li>{@code type}: Type of the log record.</li>
 * </ul>
 */
public abstract class LogRecord {

    protected final long lsn;
    protected final long prevLsn;
    protected final long txnId;
    protected final LogRecordType type;

    protected LogRecord(long lsn, long prevLsn, long txnId, LogRecordType type) {
        this.lsn = lsn;
        this.prevLsn = prevLsn;
        this.txnId = txnId;
        this.type = Objects.requireNonNull(type, "type must not be null");
    }

    public long getLsn() {
        return lsn;
    }

    public long getPrevLsn() {
        return prevLsn;
    }

    public long getTxnId() {
        return txnId;
    }

    public LogRecordType getType() {
        return type;
    }

    /**
     * Returns a copy of this log record with an updated LSN.
     */
    public abstract LogRecord withLsn(long newLsn);

    @Override
    public String toString() {
        return "%s{lsn=%d, prevLsn=%d, txnId=%d}".formatted(type, lsn, prevLsn, txnId);
    }
}
