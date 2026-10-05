package dev.koradb.storage.wal;

import java.util.Arrays;
import java.util.Objects;

/**
 * Compensation Log Record (CLR) written during the ARIES Undo pass to log the
 * reversal of an update.
 *
 * <p>Crucially, CLRs store an {@code undoNextLsn} pointer: the LSN of the next
 * update record of the transaction that still needs to be undone. If the system
 * crashes again during recovery, the undo pass follows {@code undoNextLsn},
 * guaranteeing that previously compensated actions are never undone twice.
 */
public final class ClrLogRecord extends LogRecord {

    private final String tableName;
    private final int pageId;
    private final int slotIndex;
    private final byte[] restoredImage;
    private final long undoNextLsn;

    public ClrLogRecord(long lsn, long prevLsn, long txnId, String tableName, int pageId, int slotIndex,
                        byte[] restoredImage, long undoNextLsn) {
        super(lsn, prevLsn, txnId, LogRecordType.CLR);
        this.tableName = Objects.requireNonNull(tableName, "tableName must not be null");
        this.pageId = pageId;
        this.slotIndex = slotIndex;
        this.restoredImage = (restoredImage != null) ? restoredImage.clone() : new byte[0];
        this.undoNextLsn = undoNextLsn;
    }

    public ClrLogRecord(long prevLsn, long txnId, String tableName, int pageId, int slotIndex,
                        byte[] restoredImage, long undoNextLsn) {
        this(0L, prevLsn, txnId, tableName, pageId, slotIndex, restoredImage, undoNextLsn);
    }

    public String getTableName() {
        return tableName;
    }

    public int getPageId() {
        return pageId;
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    public byte[] getRestoredImage() {
        return restoredImage.clone();
    }

    public long getUndoNextLsn() {
        return undoNextLsn;
    }

    @Override
    public LogRecord withLsn(long newLsn) {
        return new ClrLogRecord(newLsn, getPrevLsn(), getTxnId(), tableName, pageId, slotIndex, restoredImage, undoNextLsn);
    }

    @Override
    public String toString() {
        return "ClrLogRecord{" +
                "lsn=" + getLsn() +
                ", prevLsn=" + getPrevLsn() +
                ", txnId=" + getTxnId() +
                ", table='" + tableName + '\'' +
                ", pageId=" + pageId +
                ", slot=" + slotIndex +
                ", undoNextLsn=" + undoNextLsn +
                ", restoredBytes=" + restoredImage.length +
                '}';
    }
}
