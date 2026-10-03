package dev.koradb.storage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * A fixed-size (4 KB) block of bytes — the fundamental unit of storage in KoraDB.
 *
 * <p>A {@code Page} wraps a {@code byte[PAGE_SIZE]} array and provides
 * low-level read/write methods with explicit byte offsets. It does not know
 * about page headers, slots, or records — those are the responsibility of
 * {@link SlottedPage}. The separation keeps this class small and testable.
 *
 * <h2>Byte order</h2>
 * <p>All multi-byte values are stored in <strong>big-endian</strong> order
 * (most-significant byte first). This is consistent with network byte order,
 * easy to inspect in a hex editor, and independent of JVM endianness.
 *
 * <h2>Design</h2>
 * <p>The internal byte array is the single source of truth. All read/write
 * methods index directly into it — no copies, no allocations on the hot path.
 *
 * <h2>Thread safety</h2>
 * <p>Not thread-safe. In Milestone 3 the buffer pool will hold a latch
 * (read-write lock) per page frame.
 */
public final class Page {

    private final int    pageId;
    private final byte[] data;

    /**
     * Creates a new blank page filled with zero bytes.
     *
     * @param pageId the page's position in the table file (zero-based)
     */
    public Page(int pageId) {
        this.pageId = pageId;
        this.data   = new byte[StorageConfig.PAGE_SIZE];
    }

    /**
     * Wraps an existing byte array as a page.
     * Used when reading a page back from disk — no copy is made.
     *
     * @param pageId the page's position in the table file
     * @param data   exactly {@link StorageConfig#PAGE_SIZE} bytes
     * @throws IllegalArgumentException if data.length != PAGE_SIZE
     */
    public Page(int pageId, byte[] data) {
        if (data.length != StorageConfig.PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Page data must be exactly %d bytes, got %d"
                            .formatted(StorageConfig.PAGE_SIZE, data.length));
        }
        this.pageId = pageId;
        this.data   = data;
    }

    // =========================================================================
    // Identity
    // =========================================================================

    public int getPageId() { return pageId; }

    /** Returns a direct reference to the internal byte array (no copy). */
    public byte[] getData() { return data; }

    // =========================================================================
    // Primitive read methods
    // =========================================================================

    /**
     * Reads a single byte at the given offset.
     *
     * @param offset byte offset within the page [0, PAGE_SIZE)
     */
    public byte readByte(int offset) {
        checkBounds(offset, 1);
        return data[offset];
    }

    /**
     * Reads a 2-byte big-endian signed short.
     *
     * @param offset byte offset within the page [0, PAGE_SIZE - 1)
     */
    public short readShort(int offset) {
        checkBounds(offset, 2);
        return (short) (((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF));
    }

    /**
     * Reads a 4-byte big-endian signed int.
     *
     * @param offset byte offset within the page [0, PAGE_SIZE - 3)
     */
    public int readInt(int offset) {
        checkBounds(offset, 4);
        return ((data[offset]     & 0xFF) << 24)
             | ((data[offset + 1] & 0xFF) << 16)
             | ((data[offset + 2] & 0xFF) <<  8)
             |  (data[offset + 3] & 0xFF);
    }

    /**
     * Reads an 8-byte big-endian signed long.
     *
     * @param offset byte offset within the page [0, PAGE_SIZE - 7)
     */
    public long readLong(int offset) {
        checkBounds(offset, 8);
        return ((long)(data[offset]     & 0xFF) << 56)
             | ((long)(data[offset + 1] & 0xFF) << 48)
             | ((long)(data[offset + 2] & 0xFF) << 40)
             | ((long)(data[offset + 3] & 0xFF) << 32)
             | ((long)(data[offset + 4] & 0xFF) << 24)
             | ((long)(data[offset + 5] & 0xFF) << 16)
             | ((long)(data[offset + 6] & 0xFF) <<  8)
             | ((long)(data[offset + 7] & 0xFF));
    }

    /**
     * Reads {@code length} bytes starting at {@code offset} into a new array.
     *
     * @param offset byte offset within the page
     * @param length number of bytes to read
     * @return a new byte array of exactly {@code length} bytes
     */
    public byte[] readBytes(int offset, int length) {
        checkBounds(offset, length);
        return Arrays.copyOfRange(data, offset, offset + length);
    }

    // =========================================================================
    // Primitive write methods
    // =========================================================================

    /**
     * Writes a single byte at the given offset.
     */
    public void writeByte(int offset, byte value) {
        checkBounds(offset, 1);
        data[offset] = value;
    }

    /**
     * Writes a 2-byte big-endian short.
     */
    public void writeShort(int offset, short value) {
        checkBounds(offset, 2);
        data[offset]     = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte)  (value        & 0xFF);
    }

    /**
     * Writes a 4-byte big-endian int.
     */
    public void writeInt(int offset, int value) {
        checkBounds(offset, 4);
        data[offset]     = (byte) ((value >> 24) & 0xFF);
        data[offset + 1] = (byte) ((value >> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >>  8) & 0xFF);
        data[offset + 3] = (byte)  (value         & 0xFF);
    }

    /**
     * Writes an 8-byte big-endian long.
     */
    public void writeLong(int offset, long value) {
        checkBounds(offset, 8);
        data[offset]     = (byte) ((value >> 56) & 0xFF);
        data[offset + 1] = (byte) ((value >> 48) & 0xFF);
        data[offset + 2] = (byte) ((value >> 40) & 0xFF);
        data[offset + 3] = (byte) ((value >> 32) & 0xFF);
        data[offset + 4] = (byte) ((value >> 24) & 0xFF);
        data[offset + 5] = (byte) ((value >> 16) & 0xFF);
        data[offset + 6] = (byte) ((value >>  8) & 0xFF);
        data[offset + 7] = (byte)  (value         & 0xFF);
    }

    /**
     * Writes a byte array at the given offset.
     *
     * @param offset byte offset within the page
     * @param bytes  bytes to write
     */
    public void writeBytes(int offset, byte[] bytes) {
        checkBounds(offset, bytes.length);
        System.arraycopy(bytes, 0, data, offset, bytes.length);
    }

    /**
     * Zeroes out a range of bytes within the page.
     * Used to clear deleted records or reset a slot entry.
     *
     * @param offset start offset (inclusive)
     * @param length number of bytes to zero
     */
    public void zero(int offset, int length) {
        checkBounds(offset, length);
        Arrays.fill(data, offset, offset + length, (byte) 0);
    }

    /** Zeroes the entire page, preserving only the page ID. */
    public void clear() {
        Arrays.fill(data, (byte) 0);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private void checkBounds(int offset, int length) {
        if (offset < 0 || offset + length > StorageConfig.PAGE_SIZE) {
            throw new IndexOutOfBoundsException(
                    "Page access out of bounds: offset=%d length=%d pageSize=%d"
                            .formatted(offset, length, StorageConfig.PAGE_SIZE));
        }
    }

    @Override
    public String toString() {
        return "Page{id=%d}".formatted(pageId);
    }
}
