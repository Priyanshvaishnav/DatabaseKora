package dev.koradb.storage.wal;

/**
 * Thrown when a WAL log record is malformed, has an invalid magic identifier,
 * or fails CRC32 checksum verification.
 */
public class CorruptedLogRecordException extends RuntimeException {

    public CorruptedLogRecordException(String message) {
        super(message);
    }

    public CorruptedLogRecordException(String message, Throwable cause) {
        super(message, cause);
    }
}
