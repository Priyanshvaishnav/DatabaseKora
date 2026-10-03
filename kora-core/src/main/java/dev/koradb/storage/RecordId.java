package dev.koradb.storage;

/**
 * A permanent, unique address for a single record within a table.
 *
 * <p>A Record ID (RID) is the pair {@code (pageId, slotIndex)}:
 * <ul>
 *   <li>{@code pageId} — zero-based index of the page in the table file</li>
 *   <li>{@code slotIndex} — zero-based index into the page's slot directory</li>
 * </ul>
 *
 * <h2>Stability guarantee</h2>
 * <p>A slot index is never reused or shifted after insertion. Deleting slot 3
 * marks it as a tombstone; slot 4 remains slot 4. This means a RID written
 * into a B+ Tree leaf node (Milestone 4) continues to point to the correct
 * record even after other records on the same page are deleted.
 *
 * <h2>On-disk encoding (8 bytes)</h2>
 * <pre>
 * pageId    (4 bytes, big-endian int)
 * slotIndex (4 bytes, big-endian int)
 * </pre>
 *
 * @param pageId    zero-based page number within the table file
 * @param slotIndex zero-based slot index within the page's slot directory
 */
public record RecordId(int pageId, int slotIndex) {

    public RecordId {
        if (pageId < 0) throw new IllegalArgumentException(
                "pageId must be >= 0, got: " + pageId);
        if (slotIndex < 0) throw new IllegalArgumentException(
                "slotIndex must be >= 0, got: " + slotIndex);
    }

    @Override
    public String toString() {
        return "RID(%d,%d)".formatted(pageId, slotIndex);
    }
}
