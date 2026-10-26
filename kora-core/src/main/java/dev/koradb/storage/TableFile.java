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
    private volatile Schema           schema;
    private final BufferPool       bufferPool;
    private final DiskPageManager  diskManager;
    private volatile RecordSerializer serializer;
    private final Path             filePath;

    /**
     * Per-table latch. Writers mutate page byte arrays in place, so readers must not deserialize a
     * page while a writer is changing it, and two writers must not pick the same free slot.
     */
    private final java.util.concurrent.locks.ReentrantReadWriteLock latch = new java.util.concurrent.locks.ReentrantReadWriteLock();

    /** First page an insert should try (M4); see insertRow. */
    private volatile int insertHintPage = 0;
    /** Number of row writes since open; lets callers cache derived data such as statistics. */
    private final java.util.concurrent.atomic.AtomicLong modCount = new java.util.concurrent.atomic.AtomicLong();

    public long getModCount() {
        return modCount.get();
    }

    private <T> T writeLocked(java.util.function.Supplier<T> body) {
        latch.writeLock().lock();
        try { return body.get(); } finally { latch.writeLock().unlock(); }
    }

    private <T> T readLocked(java.util.function.Supplier<T> body) {
        latch.readLock().lock();
        try { return body.get(); } finally { latch.readLock().unlock(); }
    }

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
        this.filePath    = filePath;
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

    /**
     * Switches to {@code newSchema} (ALTER TABLE ADD COLUMN). Existing records are not rewritten:
     * they are read with the old column count and get NULL for the added columns.
     */
    public void alterSchema(Schema newSchema) {
        writeLocked(() -> {
            this.schema = newSchema;
            this.serializer = new RecordSerializer(newSchema);
            modCount.incrementAndGet();
            return null;
        });
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
        return writeLocked(() -> {
        if (txn != null) {
            row.setXmin(txn.getId().value());
            row.setXmax(0L);
        }
        byte[] record = serializer.serialize(toastIfNeeded(row, txn));

        // Start at the last page that had room (M4): pages before it are full, because tombstoned
        // slots never give space back. Without the hint every insert rescanned the whole file.
        int totalPages = diskManager.pageCount();
        int startPage = Math.max(0, Math.min(insertHintPage, totalPages - 1));
        for (int pid = startPage; pid < totalPages; pid++) {
            BufferFrame frame = bufferPool.pin(pid);
            try {
                SlottedPage sp = new SlottedPage(frame.getPage());
                if (sp.freeSpace() >= StorageConfig.SLOT_SIZE + record.length) {
                    insertHintPage = pid;
                    modCount.incrementAndGet();
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
            insertHintPage = frame.getPage().getPageId();
            modCount.incrementAndGet();
            int         slot = sp.insertRecord(record);
            applyWalAndMarkDirty(frame, slot, new byte[0], record, txn);
            RecordId rid = new RecordId(frame.getPage().getPageId(), slot);
            log.debug("Inserted row at {} (new page)", rid);
            return rid;
        } finally {
            bufferPool.unpin(frame);
        }
    });
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
        return scanWithRids(dev.koradb.mvcc.MvccContext.getReadView());
    }

    /**
     * Reads every row matching the given ReadView snapshot, paired with their RIDs.
     */
    public List<RidRow> scanWithRids(dev.koradb.mvcc.ReadView readView) {
        return readLocked(() -> {
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
                        if (readView != null) {
                            if (readView.isVisible(r.getXmin(), r.getXmax())) {
                                result.add(new RidRow(new RecordId(pid, slot), detoast(r)));
                            }
                        } else {
                            if (r.getXmax() == 0L) {
                                result.add(new RidRow(new RecordId(pid, slot), detoast(r)));
                            }
                        }
                    }
                }
            } finally {
                bufferPool.unpin(frame);
            }
        }
        return result;
    });
    }

    /**
     * Reads every live row (no RIDs). Used by SELECT.
     */
    /** Every stored (non-tombstoned) version, ignoring MVCC visibility. Used to build indexes. */
    public List<RidRow> scanAllVersions() {
        return readLocked(() -> {
            List<RidRow> result = new ArrayList<>();
            int totalPages = diskManager.pageCount();
            for (int pid = 0; pid < totalPages; pid++) {
                BufferFrame frame = bufferPool.pin(pid);
                try {
                    SlottedPage sp = new SlottedPage(frame.getPage());
                    for (int slot = 0; slot < sp.slotCount(); slot++) {
                        if (sp.isAlive(slot)) {
                            result.add(new RidRow(new RecordId(pid, slot), detoastLenient(serializer.deserialize(sp.getRecord(slot)))));
                        }
                    }
                } finally {
                    bufferPool.unpin(frame);
                }
            }
            return result;
        });
    }

    public List<Row> scanAll() {
        return scanAll(dev.koradb.mvcc.MvccContext.getReadView());
    }

    /**
     * Reads every row matching the given ReadView snapshot (no RIDs).
     */
    public List<Row> scanAll(dev.koradb.mvcc.ReadView readView) {
        return readLocked(() -> {
        List<Row> result = new ArrayList<>();
        int totalPages   = diskManager.pageCount();

        for (int pid = 0; pid < totalPages; pid++) {
            BufferFrame frame = bufferPool.pin(pid);
            try {
                SlottedPage sp = new SlottedPage(frame.getPage());
                for (int slot = 0; slot < sp.slotCount(); slot++) {
                    if (sp.isAlive(slot)) {
                        byte[] record = sp.getRecord(slot);
                        Row    r      = serializer.deserialize(record);
                        if (readView != null) {
                            if (readView.isVisible(r.getXmin(), r.getXmax())) {
                                result.add(detoast(r));
                            }
                        } else {
                            if (r.getXmax() == 0L) {
                                result.add(detoast(r));
                            }
                        }
                    }
                }
            } finally {
                bufferPool.unpin(frame);
            }
        }

        log.debug("Scan returned {} row(s)", result.size());
        return result;
    });
    }

    /**
     * Reads a single row by its {@link RecordId}.
     */
    /** Reads the stored tuple at {@code rid} ignoring MVCC visibility; null if the slot is dead/absent. */
    public Row readRawRow(RecordId rid) {
        return readLocked(() -> {
        if (rid.pageId() < 0 || rid.pageId() >= diskManager.pageCount()) return null;
        BufferFrame frame = bufferPool.pin(rid.pageId());
        try {
            SlottedPage sp = new SlottedPage(frame.getPage());
            if (!sp.isAlive(rid.slotIndex())) return null;
            return detoast(serializer.deserialize(sp.getRecord(rid.slotIndex())));
        } finally {
            bufferPool.unpin(frame);
        }
    });
    }

    public Row readRow(RecordId rid) {
        return readRow(rid, dev.koradb.mvcc.MvccContext.getReadView());
    }

    /**
     * Reads a single row by its {@link RecordId}, visible under the specified ReadView.
     */
    public Row readRow(RecordId rid, dev.koradb.mvcc.ReadView readView) {
        return readLocked(() -> {
        BufferFrame frame = bufferPool.pin(rid.pageId());
        try {
            SlottedPage sp = new SlottedPage(frame.getPage());
            if (!sp.isAlive(rid.slotIndex())) {
                return null;
            }
            byte[] record = sp.getRecord(rid.slotIndex());
            Row r = serializer.deserialize(record);
            if (readView != null) {
                return readView.isVisible(r.getXmin(), r.getXmax()) ? detoast(r) : null;
            } else {
                return r.getXmax() == 0L ? detoast(r) : null;
            }
        } finally {
            bufferPool.unpin(frame);
        }
    });
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
     * Marks the row version at {@code rid} deleted under MVCC by stamping its xmax.
     * Throws WriteConflictException if the row is already concurrently deleted or updated.
     */
    public void deleteRow(RecordId rid, dev.koradb.transaction.Transaction txn) {
        writeLocked(() -> {
        BufferFrame frame = bufferPool.pin(rid.pageId());
        try {
            SlottedPage sp = new SlottedPage(frame.getPage());
            if (!sp.isAlive(rid.slotIndex())) {
                return null;
            }
            if (txn == null) {
                byte[] before = sp.getRecord(rid.slotIndex());
                sp.deleteRecord(rid.slotIndex());
                applyWalAndMarkDirty(frame, rid.slotIndex(), before, new byte[0], null);
                return null;
            }
            long txnId = txn.getId().value();
            byte[] oldBytes = sp.getRecord(rid.slotIndex());
            long oldXmax = RecordSerializer.extractXmax(oldBytes);
            // An xmax left by an ABORTED transaction does not lock the row (C3).
            if (oldXmax != 0L && oldXmax != txnId && !txn.isOtherTxnAborted(oldXmax)) {
                throw new dev.koradb.mvcc.WriteConflictException(oldXmax, txnId);
            }

            modCount.incrementAndGet();
            byte[] newBytes = oldBytes.clone();
            RecordSerializer.setXmax(newBytes, txnId);
            sp.updateRecord(rid.slotIndex(), newBytes);
            applyWalAndMarkDirty(frame, rid.slotIndex(), oldBytes, newBytes, txn);
            log.debug("Deleted row at {} (stamped xmax={})", rid, txnId);
        } finally {
            bufferPool.unpin(frame);
        }
        return null;
        });
    }

    /**
     * Reclaims page space occupied by dead tuple versions whose xmax is older than lowWatermark.
     */
    public int vacuum(long lowWatermark, java.util.function.Predicate<Long> isCommitted) {
        return vacuum(lowWatermark, isCommitted, null);
    }

    /**
     * Tombstones row versions deleted by a transaction that committed before {@code lowWatermark}
     * (no snapshot can still see them). Reclaimed RIDs are reported to {@code reclaimedOut}
     * (if non-null) so index entries pointing at them can be removed.
     */
    public int vacuum(long lowWatermark, java.util.function.Predicate<Long> isCommitted,
                      java.util.List<RecordId> reclaimedOut) {
        return writeLocked(() -> {
        int reclaimed = 0;
        List<RecordId> freedChunks = new ArrayList<>();
        int totalPages = diskManager.pageCount();
        for (int pid = 0; pid < totalPages; pid++) {
            BufferFrame frame = bufferPool.pin(pid);
            try {
                SlottedPage sp = new SlottedPage(frame.getPage());
                boolean modified = false;
                for (int slot = 0; slot < sp.slotCount(); slot++) {
                    if (sp.isAlive(slot)) {
                        byte[] record = sp.getRecord(slot);
                        long xmax = RecordSerializer.extractXmax(record);
                        if (xmax != 0L && xmax < lowWatermark && isCommitted.test(xmax)) {
                            if (toast != null) collectChunks(serializer.deserialize(record), freedChunks);
                            sp.deleteRecord(slot);
                            if (reclaimedOut != null) reclaimedOut.add(new RecordId(pid, slot));
                            modified = true;
                            reclaimed++;
                        }
                    }
                }
                if (modified) {
                    bufferPool.markDirty(frame);
                }
            } finally {
                bufferPool.unpin(frame);
            }
        }
        if (!freedChunks.isEmpty()) toast.freeChunks(freedChunks);
        return reclaimed;
    });
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
        return writeLocked(() -> {
        deleteRow(rid, txn);
        return insertRow(newRow, txn);
    });
    }

    private void applyWalAndMarkDirty(BufferFrame frame, int slot, byte[] beforeImage, byte[] afterImage,
                                      dev.koradb.transaction.Transaction txn) {
        dev.koradb.storage.wal.LogManager logManager = bufferPool.getLogManager();
        if (logManager != null) {
            if (txn != null) {
                txn.bindWal(logManager, this);   // lazily writes BEGIN into THIS database's WAL
            }
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

    /**
     * Scans all pages and returns the maximum xmin or xmax found on live slots.
     */
    public long getMaxTxnId() {
        long max = 0L;
        int totalPages = diskManager.pageCount();
        for (int pid = 0; pid < totalPages; pid++) {
            BufferFrame frame = bufferPool.pin(pid);
            try {
                SlottedPage sp = new SlottedPage(frame.getPage());
                for (int slot = 0; slot < sp.slotCount(); slot++) {
                    if (sp.isAlive(slot)) {
                        byte[] record = sp.getRecord(slot);
                        long xmin = RecordSerializer.extractXmin(record);
                        long xmax = RecordSerializer.extractXmax(record);
                        if (xmin > max) max = xmin;
                        if (xmax > max) max = xmax;
                    }
                }
            } finally {
                bufferPool.unpin(frame);
            }
        }
        return max;
    }

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
        TableFile t = toast;
        if (t != null) t.close();
        bufferPool.close();
    }

    // =========================================================================
    // Runtime rollback (ARIES-style, driven by the WAL)
    // =========================================================================

    /**
     * Undoes one logged change made by {@code txnId}: restores the record's before-image in place
     * (insert -> tombstone, xmax stamp -> original bytes) and writes a CLR whose undoNextLsn is the
     * undone record's prevLsn, so a crash during rollback resumes correctly.
     *
     * @return the LSN of the CLR written
     */
    public long undoLogged(dev.koradb.storage.wal.UpdateLogRecord rec, long txnId, long prevLsn) {
        return writeLocked(() -> {
            dev.koradb.storage.wal.LogManager lm = bufferPool.getLogManager();
            BufferFrame frame = bufferPool.pin(rec.getPageId());
            try {
                SlottedPage sp = new SlottedPage(frame.getPage());
                byte[] before = rec.getBeforeImage();
                int slot = rec.getSlotIndex();
                if (before == null || before.length == 0) {
                    if (sp.isAlive(slot)) sp.deleteRecord(slot);
                } else if (slot < sp.slotCount()) {
                    sp.updateRecord(slot, before);
                } else if (slot == sp.slotCount()) {
                    sp.insertRecord(before);
                }
                long clrLsn = lm.appendClr(txnId, prevLsn, rec.getTableName(), rec.getPageId(),
                        slot, before == null ? new byte[0] : before, rec.getPrevLsn());
                frame.getPage().setPageLsn(clrLsn);
                bufferPool.markDirty(frame, clrLsn);
                return clrLsn;
            } finally {
                bufferPool.unpin(frame);
            }
        });
    }

    /**
     * Fallback undo for tables without a WAL: clears an xmax stamped by {@code txnId} on the stored
     * tuple (undo of DELETE / of the old version of an UPDATE).
     */
    public void clearXmax(RecordId rid, long txnId) {
        writeLocked(() -> {
            BufferFrame frame = bufferPool.pin(rid.pageId());
            try {
                SlottedPage sp = new SlottedPage(frame.getPage());
                if (!sp.isAlive(rid.slotIndex())) return null;
                byte[] bytes = sp.getRecord(rid.slotIndex());
                if (RecordSerializer.extractXmax(bytes) != txnId) return null;
                byte[] restored = bytes.clone();
                RecordSerializer.setXmax(restored, 0L);
                sp.updateRecord(rid.slotIndex(), restored);
                bufferPool.markDirty(frame);
                return null;
            } finally {
                bufferPool.unpin(frame);
            }
        });
    }


    // =========================================================================
    // Out-of-line storage for large values (TOAST-style overflow)
    // =========================================================================

    /** Prefix of an in-row pointer to an out-of-line value. Values starting with it are always stored out of line. */
    static final String TOAST_MAGIC = "\u0000\u0001KORA-TOAST:";
    /** Largest single VARCHAR value accepted (stored out of line). */
    public static final int MAX_VARCHAR_BYTES = 16 * 1024 * 1024;
    /** Out-of-line chunk payload size (UTF-8 bytes); several chunks fit on one page. */
    private static final int CHUNK_BYTES = 1800;
    /** Rows are kept to about half a page so a page holds more than one row. */
    private static final int ROW_TARGET_BYTES = StorageConfig.MAX_RECORD_SIZE / 2;
    /** VARCHAR values at least this large are candidates for out-of-line storage. */
    private static final int TOAST_MIN_VALUE_BYTES = 256;
    private static final Schema TOAST_SCHEMA = new Schema(java.util.List.of(
            new dev.koradb.sql.ast.ColumnDef("chunk", dev.koradb.sql.ast.DataType.VARCHAR)));

    private volatile TableFile toast;

    /** Path of the overflow file that belongs to a table file. */
    public static Path toastPathFor(Path tableFilePath) {
        String fn = tableFilePath.getFileName().toString();
        String base = fn.endsWith(".kdb") ? fn.substring(0, fn.length() - 4) : fn;
        return tableFilePath.resolveSibling(base + ".toast.kdb");
    }

    /** True for the WAL table name of an overflow file ("orders.toast"). */
    public static boolean isToastName(String tableName) {
        return tableName != null && tableName.toLowerCase().endsWith(".toast");
    }

    /**
     * The overflow table of this table, opened on first use. With {@code create=false} it is only
     * opened when its file already exists (recovery / replication / reads); returns null otherwise.
     */
    public TableFile toastTable(boolean create) {
        TableFile t = toast;
        if (t != null) return t;
        synchronized (this) {
            if (toast == null) {
                Path tp = toastPathFor(filePath);
                if (!create && !java.nio.file.Files.exists(tp)) return null;
                toast = new TableFile(TOAST_SCHEMA, tp, new BufferPool(new DiskPageManager(tp), bufferPool.getLogManager()));
            }
            return toast;
        }
    }

    /** Returns a copy of {@code row} whose large VARCHAR values are moved out of line (or {@code row} itself). */
    private Row toastIfNeeded(Row row, dev.koradb.transaction.Transaction txn) {
        if (isToastName(tableName)) return row;            // chunks are always small and stored verbatim
        java.util.List<dev.koradb.sql.ast.ColumnDef> cols = schema.columns();
        int total = 0;
        java.util.List<Object[]> candidates = new java.util.ArrayList<>();   // {column name, utf8 length}
        boolean forced = false;
        for (dev.koradb.sql.ast.ColumnDef c : cols) {
            Object v = row.get(c.name());
            if (v instanceof String str && c.dataType() == dev.koradb.sql.ast.DataType.VARCHAR) {
                int len = str.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (len > MAX_VARCHAR_BYTES) {
                    throw new StorageException("VARCHAR value for column '%s' is %d bytes; the maximum is %d bytes"
                            .formatted(c.name(), len, MAX_VARCHAR_BYTES));
                }
                total += len + 2;
                if (str.startsWith(TOAST_MAGIC)) forced = true;
                if (len >= TOAST_MIN_VALUE_BYTES || str.startsWith(TOAST_MAGIC)) candidates.add(new Object[]{c.name(), len});
            } else {
                total += 8;
            }
        }
        if (!forced && total <= ROW_TARGET_BYTES) return row;
        candidates.sort((a, b) -> Integer.compare((Integer) b[1], (Integer) a[1]));
        java.util.Map<String, Object> vals = new java.util.LinkedHashMap<>(row.values());
        for (Object[] cand : candidates) {
            String col = (String) cand[0];
            String str = (String) vals.get(col);
            boolean mustToast = str.startsWith(TOAST_MAGIC);
            if (!mustToast && total <= ROW_TARGET_BYTES) continue;
            String pointer = writeOutOfLine(str, txn);
            total -= (Integer) cand[1];
            total += pointer.length();
            vals.put(col, pointer);
        }
        Row stored = new Row(vals, row.getXmin(), row.getXmax());
        return stored;
    }

    /** Writes a value as chunks into the overflow table; returns the in-row pointer. */
    private String writeOutOfLine(String value, dev.koradb.transaction.Transaction txn) {
        String ptr = writeChunks(value, txn);
        // A very large value has a long chunk list; store the list itself out of line ("I" = indirect).
        while (ptr.length() > MAX_POINTER_CHARS) {
            ptr = TOAST_MAGIC + "I" + writeChunks(ptr, txn).substring(TOAST_MAGIC.length());
        }
        return ptr;
    }

    private static final int MAX_POINTER_CHARS = 512;

    private String writeChunks(String value, dev.koradb.transaction.Transaction txn) {
        TableFile t = toastTable(true);
        StringBuilder ptr = new StringBuilder(TOAST_MAGIC).append(value.length());
        int i = 0;
        while (i < value.length()) {
            int end = i, bytes = 0;
            while (end < value.length()) {
                int cp = value.codePointAt(end);
                int cpBytes = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
                if (bytes + cpBytes > CHUNK_BYTES) break;
                bytes += cpBytes;
                end += Character.charCount(cp);
            }
            Row chunk = new Row(new java.util.LinkedHashMap<>(java.util.Map.of("chunk", value.substring(i, end))));
            RecordId rid = t.insertRow(chunk, txn);   // WAL-logged under the same transaction
            ptr.append(':').append(rid.pageId()).append('.').append(rid.slotIndex());
            i = end;
        }
        return ptr.toString();
    }

    /** Replaces out-of-line pointers in {@code r} by the stored values. */
    private Row detoast(Row r) {
        if (r == null || isToastName(tableName)) return r;
        java.util.Map<String, Object> vals = null;
        for (java.util.Map.Entry<String, Object> e : r.values().entrySet()) {
            if (e.getValue() instanceof String str && str.startsWith(TOAST_MAGIC)) {
                if (vals == null) vals = new java.util.LinkedHashMap<>(r.values());
                vals.put(e.getKey(), readOutOfLine(str));
            }
        }
        return vals == null ? r : new Row(vals, r.getXmin(), r.getXmax());
    }

    private String readOutOfLine(String pointer) {
        String body = pointer.substring(TOAST_MAGIC.length());
        if (body.startsWith("I")) {
            return readOutOfLine(readOutOfLine(TOAST_MAGIC + body.substring(1)));
        }
        String[] parts = body.split(":");
        int length = Integer.parseInt(parts[0]);
        TableFile t = toastTable(false);
        if (t == null) {
            throw new StorageException("Overflow file for table '" + tableName + "' is missing");
        }
        StringBuilder sb = new StringBuilder(length);
        for (int k = 1; k < parts.length; k++) {
            int dot = parts[k].indexOf('.');
            RecordId rid = new RecordId(Integer.parseInt(parts[k].substring(0, dot)), Integer.parseInt(parts[k].substring(dot + 1)));
            Row chunk = t.readRawRow(rid);
            if (chunk == null) {
                throw new StorageException("Overflow chunk " + rid + " of table '" + tableName + "' is missing");
            }
            sb.append((String) chunk.get("chunk"));
        }
        return sb.toString();
    }


    /** Like {@link #detoast} but leaves a pointer in place if its chunks are gone (dead versions after VACUUM). */
    private Row detoastLenient(Row r) {
        try {
            return detoast(r);
        } catch (RuntimeException e) {
            return r;
        }
    }

    /** Adds the overflow chunks referenced by {@code r} (including indirect chunk lists) to {@code out}. */
    private void collectChunks(Row r, List<RecordId> out) {
        for (Object v : r.values().values()) {
            if (v instanceof String str && str.startsWith(TOAST_MAGIC)) {
                try {
                    collectPointer(str, out);
                } catch (RuntimeException e) {
                    log.warn("Could not resolve overflow pointer of a reclaimed row in '{}': {}", tableName, e.getMessage());
                }
            }
        }
    }

    private void collectPointer(String pointer, List<RecordId> out) {
        String body = pointer.substring(TOAST_MAGIC.length());
        boolean indirect = body.startsWith("I");
        if (indirect) body = body.substring(1);
        String[] parts = body.split(":");
        List<RecordId> rids = new ArrayList<>();
        for (int k = 1; k < parts.length; k++) {
            int dot = parts[k].indexOf('.');
            rids.add(new RecordId(Integer.parseInt(parts[k].substring(0, dot)), Integer.parseInt(parts[k].substring(dot + 1))));
        }
        if (indirect) {
            collectPointer(readOutOfLine(TOAST_MAGIC + body), out);   // the chunk list itself
        }
        out.addAll(rids);
    }

    /** Physically frees overflow chunks of reclaimed rows (VACUUM; like the main table, not WAL-logged). */
    void freeChunks(List<RecordId> rids) {
        writeLocked(() -> {
            for (RecordId rid : rids) {
                if (rid.pageId() >= diskManager.pageCount()) continue;
                BufferFrame frame = bufferPool.pin(rid.pageId());
                try {
                    SlottedPage sp = new SlottedPage(frame.getPage());
                    if (rid.slotIndex() < sp.slotCount() && sp.isAlive(rid.slotIndex())) {
                        sp.deleteRecord(rid.slotIndex());
                        bufferPool.markDirty(frame);
                    }
                } finally {
                    bufferPool.unpin(frame);
                }
            }
            modCount.addAndGet(rids.size());
            insertHintPage = 0;
            return null;
        });
    }

}
