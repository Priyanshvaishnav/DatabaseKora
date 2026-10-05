package dev.koradb.storage.wal;

/**
 * Types of log records written to the Write-Ahead Log (WAL).
 */
public enum LogRecordType {
    /**
     * Indicates the beginning of a transaction.
     */
    BEGIN((byte) 1),

    /**
     * Physiological update record recording before-image (undo) and after-image (redo) of a row modification.
     */
    UPDATE((byte) 2),

    /**
     * Indicates successful transaction commit.
     */
    COMMIT((byte) 3),

    /**
     * Indicates transaction abort/rollback.
     */
    ABORT((byte) 4),

    /**
     * Checkpoint initiation record.
     */
    CHECKPOINT_BEGIN((byte) 5),

    /**
     * Checkpoint completion record with active transaction and dirty page tables.
     */
    CHECKPOINT_END((byte) 6),

    /**
     * Compensation Log Record (CLR) written during undo pass to log the reversal of an update.
     * Contains undoNextLSN to avoid undoing already-compensated actions.
     */
    CLR((byte) 7),

    /**
     * Indicates complete termination and cleanup of a transaction.
     */
    END((byte) 8);

    private final byte code;

    LogRecordType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static LogRecordType fromCode(byte code) {
        return switch (code) {
            case 1 -> BEGIN;
            case 2 -> UPDATE;
            case 3 -> COMMIT;
            case 4 -> ABORT;
            case 5 -> CHECKPOINT_BEGIN;
            case 6 -> CHECKPOINT_END;
            case 7 -> CLR;
            case 8 -> END;
            default -> throw new IllegalArgumentException("Unknown LogRecordType code: " + code);
        };
    }
}
