package dev.koradb.storage.wal;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Checkpoint completion record storing the active transaction table and dirty page table.
 */
public final class CheckpointEndRecord extends LogRecord {

    // Maps txnId -> lastLsn for active transactions
    private final Map<Long, Long> activeTransactionTable;
    // Maps pageId -> recLsn (oldest unwritten LSN on that page)
    private final Map<Integer, Long> dirtyPageTable;

    public CheckpointEndRecord(long lsn, Map<Long, Long> activeTransactionTable, Map<Integer, Long> dirtyPageTable) {
        super(lsn, 0L, 0L, LogRecordType.CHECKPOINT_END);
        this.activeTransactionTable = Collections.unmodifiableMap(Objects.requireNonNull(activeTransactionTable));
        this.dirtyPageTable = Collections.unmodifiableMap(Objects.requireNonNull(dirtyPageTable));
    }

    public CheckpointEndRecord(Map<Long, Long> activeTransactionTable, Map<Integer, Long> dirtyPageTable) {
        this(0L, activeTransactionTable, dirtyPageTable);
    }

    public Map<Long, Long> getActiveTransactionTable() {
        return activeTransactionTable;
    }

    public Map<Integer, Long> getDirtyPageTable() {
        return dirtyPageTable;
    }

    @Override
    public LogRecord withLsn(long newLsn) {
        return new CheckpointEndRecord(newLsn, activeTransactionTable, dirtyPageTable);
    }
}
