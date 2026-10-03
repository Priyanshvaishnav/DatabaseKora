package dev.koradb.storage;

import dev.koradb.exception.StorageException;

/**
 * Implements the slotted-page layout on top of a raw {@link Page}.
 *
 * <h2>Layout recap</h2>
 * <pre>
 * [PAGE HEADER 16 bytes]
 * [SLOT 0: offset(2) + length(2)]
 * [SLOT 1: offset(2) + length(2)]
 * ...                              <- freeStart grows → as slots are added
 *        FREE SPACE
 * ...                              <- freeEnd grows ← as records are added
 * [RECORD N-1]
 * [RECORD 1]
 * [RECORD 0]
 * </pre>
 *
 * <h2>Slot encoding</h2>
 * <ul>
 *   <li>Offset field = {@link StorageConfig#SLOT_DELETED} (-1) means the slot
 *       is a tombstone (record was deleted).</li>
 *   <li>Slot indices are <em>never reused</em> — this preserves the stability
 *       guarantee required by B+ Tree leaf pointers in Milestone 4.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>Not thread-safe. The buffer pool (M3) will hold a per-page latch.
 */
public final class SlottedPage {

    private final Page       page;
    private final PageHeader header;

    /**
     * Wraps an existing {@link Page} (either freshly created or read from disk).
     *
     * @param page the underlying 4 KB buffer
     */
    public SlottedPage(Page page) {
        this.page   = page;
        this.header = new PageHeader(page);
    }

    /**
     * Initialises this page as a new, empty slotted page.
     * Must be called exactly once when a page is first allocated.
     */
    public void initialise() {
        header.initialise(page.getPageId());
    }

    // =========================================================================
    // Insert
    // =========================================================================

    /**
     * Inserts a record into this page.
     *
     * <p>Steps:
     * <ol>
     *   <li>Check free space</li>
     *   <li>Write record bytes at the current {@code freeEnd} (moving it left)</li>
     *   <li>Write a slot entry at the current {@code freeStart} (moving it right)</li>
     *   <li>Update header fields</li>
     * </ol>
     *
     * @param record the serialized record bytes
     * @return the slot index assigned to this record
     * @throws StorageException if there is not enough free space on the page
     */
    public int insertRecord(byte[] record) {
        if (!header.canInsert(record.length)) {
            throw new StorageException(
                    "Not enough space on page %d: need %d bytes, have %d"
                            .formatted(page.getPageId(),
                                       record.length + StorageConfig.SLOT_SIZE,
                                       header.freeSpace()));
        }

        // 1. Write record at the end of the page (growing leftward)
        int newFreeEnd   = Short.toUnsignedInt(header.getFreeEnd()) - record.length;
        page.writeBytes(newFreeEnd, record);

        // 2. Determine the slot index (= current number of slots = freeStart pos)
        int slotIndex    = slotCount();
        int slotOffset   = slotDirectoryOffset(slotIndex);

        // 3. Write the slot entry: (recordOffset, recordLength)
        page.writeShort(slotOffset,     (short) newFreeEnd);
        page.writeShort(slotOffset + 2, (short) record.length);

        // 4. Update header: advance freeStart by SLOT_SIZE, pull freeEnd left
        int newFreeStart = Short.toUnsignedInt(header.getFreeStart()) + StorageConfig.SLOT_SIZE;
        header.setFreeStart((short) newFreeStart);
        header.setFreeEnd  ((short) newFreeEnd);
        header.incrementRecordCount();

        return slotIndex;
    }

    // =========================================================================
    // Read
    // =========================================================================

    /**
     * Reads the record at the given slot index.
     *
     * @param slotIndex the slot index returned by {@link #insertRecord}
     * @return the record bytes
     * @throws StorageException if the slot is deleted or out of range
     */
    public byte[] getRecord(int slotIndex) {
        validateSlotIndex(slotIndex);

        int slotOffset = slotDirectoryOffset(slotIndex);
        short recOffset = page.readShort(slotOffset);
        short recLength = page.readShort(slotOffset + 2);

        if (recOffset == StorageConfig.SLOT_DELETED) {
            throw new StorageException(
                    "Slot %d on page %d has been deleted".formatted(slotIndex, page.getPageId()));
        }

        return page.readBytes(Short.toUnsignedInt(recOffset), Short.toUnsignedInt(recLength));
    }

    /**
     * Returns true if the slot at {@code slotIndex} is a live (non-deleted) record.
     */
    public boolean isAlive(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= slotCount()) return false;
        int   slotOffset = slotDirectoryOffset(slotIndex);
        short recOffset  = page.readShort(slotOffset);
        return recOffset != StorageConfig.SLOT_DELETED;
    }

    // =========================================================================
    // Delete
    // =========================================================================

    /**
     * Marks a slot as deleted (tombstone).
     *
     * <p>The record bytes are left in place — the space is not reclaimed
     * until the page is compacted (not implemented in M2). Only the slot
     * offset field is set to {@link StorageConfig#SLOT_DELETED}.
     *
     * @param slotIndex the slot to delete
     * @throws StorageException if the slot is already deleted or out of range
     */
    public void deleteRecord(int slotIndex) {
        validateSlotIndex(slotIndex);

        int slotOffset = slotDirectoryOffset(slotIndex);
        if (page.readShort(slotOffset) == StorageConfig.SLOT_DELETED) {
            throw new StorageException(
                    "Slot %d on page %d is already deleted".formatted(slotIndex, page.getPageId()));
        }

        page.writeShort(slotOffset, StorageConfig.SLOT_DELETED);
        header.decrementRecordCount();
    }

    // =========================================================================
    // Metadata
    // =========================================================================

    /** Returns the total number of slots (including tombstones). */
    public int slotCount() {
        int freeStart = Short.toUnsignedInt(header.getFreeStart());
        return (freeStart - StorageConfig.PAGE_HEADER_SIZE) / StorageConfig.SLOT_SIZE;
    }

    /** Returns the number of live (non-deleted) records on this page. */
    public int liveRecordCount() {
        return Short.toUnsignedInt(header.getRecordCount());
    }

    /** Returns the number of free bytes available for a new slot + record. */
    public int freeSpace() {
        return header.freeSpace();
    }

    public int getPageId() { return page.getPageId(); }

    public Page getPage()  { return page; }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Returns the byte offset within the page where slot {@code slotIndex}'s
     * 4-byte entry lives.
     */
    private int slotDirectoryOffset(int slotIndex) {
        return StorageConfig.PAGE_HEADER_SIZE + slotIndex * StorageConfig.SLOT_SIZE;
    }

    private void validateSlotIndex(int slotIndex) {
        int count = slotCount();
        if (slotIndex < 0 || slotIndex >= count) {
            throw new StorageException(
                    "Slot index %d out of range [0, %d) on page %d"
                            .formatted(slotIndex, count, page.getPageId()));
        }
    }
}
