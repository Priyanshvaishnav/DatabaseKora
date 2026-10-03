package dev.koradb.storage;

/**
 * Reads and writes the 16-byte header at the start of every {@link Page}.
 *
 * <h2>Header layout (16 bytes, big-endian)</h2>
 * <pre>
 * Offset  Size  Field
 * ------  ----  -----
 *   0       4   pageId       — must match the page's position in the file
 *   4       2   recordCount  — number of live (non-deleted) slots
 *   6       2   freeStart    — offset of first free byte (after last slot entry)
 *   8       2   freeEnd      — offset of first byte used by records (from end)
 *  10       2   flags        — reserved (dirty/pinned bits in M3)
 *  12       4   reserved     — reserved (nextPageId for linked lists in M3)
 * </pre>
 *
 * <p>The free space within the page spans bytes [{@code freeStart}, {@code freeEnd}).
 * A new record can be inserted when
 * {@code freeEnd - freeStart >= StorageConfig.SLOT_SIZE + recordSize}.
 */
public final class PageHeader {

    // Field offsets within the page byte array
    static final int OFFSET_PAGE_ID      =  0;
    static final int OFFSET_RECORD_COUNT =  4;
    static final int OFFSET_FREE_START   =  6;
    static final int OFFSET_FREE_END     =  8;
    static final int OFFSET_FLAGS        = 10;
    static final int OFFSET_RESERVED     = 12;

    private final Page page;

    public PageHeader(Page page) {
        this.page = page;
    }

    // =========================================================================
    // Initialise a new blank page
    // =========================================================================

    /**
     * Writes the initial header for a brand-new, empty page.
     *
     * <ul>
     *   <li>{@code pageId} = the provided value</li>
     *   <li>{@code recordCount} = 0</li>
     *   <li>{@code freeStart} = PAGE_HEADER_SIZE (slots start right after header)</li>
     *   <li>{@code freeEnd} = PAGE_SIZE (records pack from the very end)</li>
     * </ul>
     */
    public void initialise(int pageId) {
        page.writeInt  (OFFSET_PAGE_ID,      pageId);
        page.writeShort(OFFSET_RECORD_COUNT, (short) 0);
        page.writeShort(OFFSET_FREE_START,   (short) StorageConfig.PAGE_HEADER_SIZE);
        page.writeShort(OFFSET_FREE_END,     (short) StorageConfig.PAGE_SIZE);
        page.writeShort(OFFSET_FLAGS,        (short) 0);
        page.writeInt  (OFFSET_RESERVED,     0);
    }

    // =========================================================================
    // Getters
    // =========================================================================

    public int   getPageId()      { return page.readInt  (OFFSET_PAGE_ID);      }
    public short getRecordCount() { return page.readShort(OFFSET_RECORD_COUNT); }
    public short getFreeStart()   { return page.readShort(OFFSET_FREE_START);   }
    public short getFreeEnd()     { return page.readShort(OFFSET_FREE_END);     }

    /** Available free bytes — space for both a new slot entry AND the record. */
    public int freeSpace() {
        return Short.toUnsignedInt(getFreeEnd()) - Short.toUnsignedInt(getFreeStart());
    }

    /** Returns true if there is enough room to insert a record of this size. */
    public boolean canInsert(int recordSize) {
        return freeSpace() >= StorageConfig.SLOT_SIZE + recordSize;
    }

    // =========================================================================
    // Setters (package-private — only SlottedPage should call these)
    // =========================================================================

    void setRecordCount(short count)  { page.writeShort(OFFSET_RECORD_COUNT, count);     }
    void setFreeStart  (short offset) { page.writeShort(OFFSET_FREE_START,   offset);    }
    void setFreeEnd    (short offset) { page.writeShort(OFFSET_FREE_END,     offset);    }

    void incrementRecordCount() { setRecordCount((short)(getRecordCount() + 1)); }
    void decrementRecordCount() { setRecordCount((short)(getRecordCount() - 1)); }
}
