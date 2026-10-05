package dev.koradb.storage.wal;

import java.util.Objects;

/**
 * Physiological update record storing before-image (undo bytes) and after-image (redo bytes)
 * for a modified slot in a database table page.
 */
public final class UpdateLogRecord extends LogRecord {

    private final String tableName;
    private final int pageId;
    private final int slotIndex;
    private final byte[] beforeImage; // null for INSERT
    private final byte[] afterImage;  // null for DELETE

    public UpdateLogRecord(long lsn, long prevLsn, long txnId, String tableName, int pageId, int slotIndex,
                           byte[] beforeImage, byte[] afterImage) {
        super(lsn, prevLsn, txnId, LogRecordType.UPDATE);
        this.tableName = Objects.requireNonNull(tableName, "tableName must not be null");
        this.pageId = pageId;
        this.slotIndex = slotIndex;
        this.beforeImage = beforeImage;
        this.afterImage = afterImage;
    }

    public UpdateLogRecord(long prevLsn, long txnId, String tableName, int pageId, int slotIndex,
                           byte[] beforeImage, byte[] afterImage) {
        this(0L, prevLsn, txnId, tableName, pageId, slotIndex, beforeImage, afterImage);
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

    public byte[] getBeforeImage() {
        return beforeImage;
    }

    public byte[] getAfterImage() {
        return afterImage;
    }

    @Override
    public LogRecord withLsn(long newLsn) {
        return new UpdateLogRecord(newLsn, prevLsn, txnId, tableName, pageId, slotIndex, beforeImage, afterImage);
    }

    @Override
    public String toString() {
        return "UpdateLogRecord{lsn=%d, prevLsn=%d, txnId=%d, table='%s', pageId=%d, slot=%d, before=%s, after=%s}"
                .formatted(lsn, prevLsn, txnId, tableName, pageId, slotIndex,
                        beforeImage != null ? beforeImage.length + "B" : "null",
                        afterImage != null ? afterImage.length + "B" : "null");
    }
}
