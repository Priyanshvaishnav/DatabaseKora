package dev.koradb.storage;

import dev.koradb.buffer.BufferFrame;
import dev.koradb.buffer.BufferPool;
import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.exception.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages disk-backed storage for a single table, with page caching via
 * the {@link BufferPool}.
 *
 * <h2>Milestone 3 upgrade</h2>
 * <p>In M2, every read and write went directly to disk via {@link DiskPageManager}.
 * In M3, all page I/O goes through a {@link BufferPool}. The pool caches
 * recently-used pages in memory using an LRU eviction policy.
 *
 * <h2>Pin / Unpin discipline</h2>
 * <p>Every call to {@code bufferPool.pin(pageId)} must be matched by a
 * {@code bufferPool.unpin(frame)} call in a {@code finally} block.
 * Failure to unpin leaks frames and will eventually exhaust the pool.
 *
 * <h2>Write path</h2>
 * <p>After modifying a page, the frame is marked dirty via
 * {@code bufferPool.markDirty(frame)}. The pool writes it to disk lazily
 * (on eviction or {@link BufferPool#flushAll()}).
 *
 * <h2>Page selection (insert)</h2>
 * <p>Insert still scans pages 0..N looking for the first page with enough
 * free space. This is O(N) — a free-space bitmap will be added in a later
 * milestone to make it O(1).
 *
 * <h2>Thread safety</h2>
 * <p>Not thread-safe. Per-table latches are added in Milestone 11.
 */
public final class TableFile implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TableFile.class);

    private final String           tableName;
    private final Schema           schema;
    private final BufferPool       bufferPool;
    private final DiskPageManager  diskManager;
    private final RecordSerializer serializer;

    /**
     * Opens (or creates) the table file at the given path with a buffer pool.
     *
     * @param schema     the table schema
     * @param filePath   path to the {@code .kdb} table file
     * @param bufferPool the shared or per-table buffer pool
     */
    public TableFile(Schema schema, Path filePath, BufferPool bufferPool) {
        this.schema      = schema;
        this.diskManager = bufferPool.getDiskManager();
        this.bufferPool  = bufferPool;
        this.serializer  = new RecordSerializer(schema);
        String fn        = filePath.getFileName().toString();
        this.tableName   = fn.endsWith(".kdb") ? fn.substring(0, fn.length() - 4) : fn;
        log.debug("TableFile opened with buffer pool: {}", filePath.getFileName());
    }

    /**
     * Convenience constructor that creates a dedicated buffer pool for this
     * table file (backward compatibility with DiskCatalog / tests).
     *
     * @param schema   the table schema
     * @param filePath path to the {@code .kdb} table file
     */
    public TableFile(Schema schema, Path filePath) {
        this(schema, filePath,
             new BufferPool(new DiskPageManager(filePath)));
    }

    /** Returns the schema associated with this table file. */
    public Schema getSchema() { return schema; }

    /** Returns the table name associated with this file. */
    public String getTableName() { return tableName; }

    /** Returns the buffer pool used by this table. */
    public BufferPool getBufferPool() { return bufferPool; }

    // =========================================================================
    // INSERT
    // =========================================================================

    /**
     * Serializes a row and inserts it into the first page with enough free space.
     * Allocates a new page if none exist or none have room.
     *
     * @param row the row to insert
     * @return the {@link RecordId} permanently identifying the inserted row
     */
    public RecordId insertRow(Row row) {
        return insertRow(row, null);
    }

    /**
     * Serializes a row and inserts it, recording WAL update log records when
     * WAL is active.
     *
     * @param row the row to insert
     * @param txn the active transaction (nullable)
     * @return the {@link RecordId} permanently identifying the inserted row
     */
    public RecordId insertRow(Row row, dev.koradb.transaction.Transaction txn) {
        byte[] record = serializer.serialize(row);

        int totalPages = diskManager.pageCount();
        for (int pid = 0; pid < totalPages; pid++) {
            BufferFrame frame = bufferPool.pin(pid);
            try {
                SlottedPage sp = new SlottedPage(frame.getPage());
                if (sp.freeSpace() >= StorageConfig.SLOT_SIZE + record.length) {
                    int slot = sp.insertRecord(record);
                    applyWalAndMarkDirty(frame, slot, new byte[0], record, txn);
                    RecordId rid = new RecordId(pid, slot);
                    log.debug("Inserted row at {}", rid);
                    return rid;
                }
            } finally {
                bufferPool.unpin(frame);
            }
        }

        // No existing page has room — allocate a new one via the pool
        BufferFrame frame = bufferPool.allocatePage();
        try {
            SlottedPage sp   = new SlottedPage(frame.getPage());
            int         slot = sp.insertRecord(record);
            applyWalAndMarkDirty(frame, slot, new byte[0], record, txn);
            RecordId rid = new RecordId(frame.getPage().getPageId(), slot);
            log.debug("Inserted row at {} (new page)", rid);
            return rid;
        } finally {
            bufferPool.unpin(frame);
        }
    }

    // =========================================================================
    // SCAN
    // =========================================================================

    /**
     * A row paired with its {@link RecordId}.
     * Used by UPDATE and DELETE for RID-based targeted modifications.
     */
    public record RidRow(RecordId rid, Row row) {}

    /**
     * Reads every live row from every page, paired with their RIDs.
     * Used by the executor for WHERE-based UPDATE and DELETE.
     */
    public List<RidRow> scanWithRids() {
        List<RidRow> result = new ArrayList<>();
        int totalPages = diskManager.pageCount();

        for (int pid = 0; pid < totalPages; pid++) {
            BufferFrame frame = bufferPool.pin(pid);
            try {
                SlottedPage sp = new SlottedPage(frame.getPage());
                for (int slot = 0; slot < sp.slotCount(); slot++) {
                    if (sp.isAlive(slot)) {
                        byte[] record = sp.getRecord(slot);
                        Row    r      = serializer.deserialize(record);
                        result.add(new RidRow(new RecordId(pid, slot), r));
                    }
                }
            } finally {
                bufferPool.unpin(frame);
            }
        }
        return result;
    }

    /**
     * Reads every live row (no RIDs). Used by SELECT.
     */
    public List<Row> scanAll() {
        List<Row> result = new ArrayList<>();
        int totalPages   = diskManager.pageCount();

        for (int pid = 0; pid < totalPages; pid++) {
            BufferFrame frame = bufferPool.pin(pid);
            try {
                SlottedPage sp = new SlottedPage(frame.getPage());
                for (int slot = 0; slot < sp.slotCount(); slot++) {
                    if (sp.isAlive(slot)) {
                        byte[] record = sp.getRecord(slot);
                        result.add(serializer.deserialize(record));
                    }
                }
            } finally {
                bufferPool.unpin(frame);
            }
        }

        log.debug("Full scan returned {} row(s)", result.size());
        return result;
    }

    /**
     * Reads a single row by its {@link RecordId}.
     */
    public Row readRow(RecordId rid) {
        BufferFrame frame = bufferPool.pin(rid.pageId());
        try {
            SlottedPage sp = new SlottedPage(frame.getPage());
            if (!sp.isAlive(rid.slotIndex())) {
                return null;
            }
            byte[] record = sp.getRecord(rid.slotIndex());
            return serializer.deserialize(record);
        } finally {
            bufferPool.unpin(frame);
        }
    }

    // =========================================================================
    // DELETE
    // =========================================================================

    /**
     * Marks the slot at {@code rid} as a tombstone.
     * The frame is marked dirty so the pool writes the page on eviction.
     */
    public void deleteRow(RecordId rid) {
        deleteRow(rid, null);
    }

    /**
     * Marks the slot at {@code rid} as a tombstone and records a WAL update record.
     */
    public void deleteRow(RecordId rid, dev.koradb.transaction.Transaction txn) {
        BufferFrame frame = bufferPool.pin(rid.pageId());
        try {
            SlottedPage sp = new SlottedPage(frame.getPage());
            byte[] oldBytes = sp.isAlive(rid.slotIndex()) ? sp.getRecord(rid.slotIndex()) : new byte[0];
            sp.deleteRecord(rid.slotIndex());
            applyWalAndMarkDirty(frame, rid.slotIndex(), oldBytes, new byte[0], txn);
        } finally {
            bufferPool.unpin(frame);
        }
        log.debug("Deleted row at {}", rid);
    }

    // =========================================================================
    // UPDATE
    // =========================================================================

    /**
     * Updates a row via delete + re-insert.
     * The new version may land on a different page.
     */
    public RecordId updateRow(RecordId rid, Row newRow) {
        return updateRow(rid, newRow, null);
    }

    /**
     * Updates a row via delete + re-insert within a transaction.
     */
    public RecordId updateRow(RecordId rid, Row newRow, dev.koradb.transaction.Transaction txn) {
        deleteRow(rid, txn);
        return insertRow(newRow, txn);
    }

    private void applyWalAndMarkDirty(BufferFrame frame, int slot, byte[] beforeImage, byte[] afterImage,
                                      dev.koradb.transaction.Transaction txn) {
        dev.koradb.storage.wal.LogManager logManager = bufferPool.getLogManager();
        if (logManager != null) {
            long txnId = (txn != null) ? txn.getId().value() : 0L;
            long prevLsn = (txn != null) ? txn.getPrevLsn() : 0L;
            long lsn = logManager.appendUpdate(txnId, prevLsn, tableName, frame.getPage().getPageId(),
                    slot, beforeImage, afterImage);
            frame.getPage().setPageLsn(lsn);
            bufferPool.markDirty(frame, lsn);
            if (txn != null) {
                txn.setPrevLsn(lsn);
            }
        } else {
            bufferPool.markDirty(frame);
        }
    }

    // =========================================================================
    // Metadata
    // =========================================================================

    /** Returns the number of pages allocated to this table. */
    public int pageCount() {
        return diskManager.pageCount();
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Flushes all dirty pages and closes the buffer pool and disk manager.
     */
    @Override
    public void close() {
        bufferPool.close();
    }
}
