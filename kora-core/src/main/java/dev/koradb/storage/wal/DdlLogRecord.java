package dev.koradb.storage.wal;

import java.util.Objects;

/**
 * Log record representing a Data Definition Language (DDL) operation (e.g. CREATE TABLE, DROP TABLE).
 */
public final class DdlLogRecord extends LogRecord {

    private final String sql;

    public DdlLogRecord(long prevLsn, long txnId, String sql) {
        this(0L, prevLsn, txnId, sql);
    }

    public DdlLogRecord(long lsn, long prevLsn, long txnId, String sql) {
        super(lsn, prevLsn, txnId, LogRecordType.DDL);
        this.sql = Objects.requireNonNull(sql, "sql must not be null");
    }

    public String getSql() {
        return sql;
    }

    @Override
    public LogRecord withLsn(long newLsn) {
        return new DdlLogRecord(newLsn, prevLsn, txnId, sql);
    }

    @Override
    public String toString() {
        return "DDL{lsn=%d, prevLsn=%d, txnId=%d, sql='%s'}".formatted(lsn, prevLsn, txnId, sql);
    }
}
