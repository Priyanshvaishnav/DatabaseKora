package dev.koradb.storage;

import java.nio.file.Path;

/**
 * Global configuration constants for the KoraDB storage engine.
 *
 * <p>All storage components read their constants from here.
 * Changing PAGE_SIZE here changes it everywhere — but note that
 * existing data files written with a different page size will be corrupt.
 * In a production system this would be stored in the data directory header.
 *
 * <h2>Page size rationale</h2>
 * <p>4096 bytes (4 KB) matches the Linux and Windows default virtual memory
 * page size. A single {@code read()} or {@code write()} system call therefore
 * transfers exactly one OS page with no partial-page overhead.
 */
public final class StorageConfig {

    /** Size of one page in bytes. Must be a power of 2. */
    public static final int PAGE_SIZE = 4096;

    /**
     * Size of the page header in bytes.
     *
     * <pre>
     * pageId      (4 bytes) — int
     * recordCount (2 bytes) — short
     * freeStart   (2 bytes) — short (byte offset where free space begins)
     * freeEnd     (2 bytes) — short (byte offset where free space ends)
     * flags       (2 bytes) — short (reserved for dirty/pinned bits in M3)
     * reserved    (4 bytes) — int  (reserved for next-page pointer in M3)
     * pageLSN     (8 bytes) — long (Write-Ahead Log Sequence Number in M8)
     * Total: 24 bytes
     * </pre>
     */
    public static final int PAGE_HEADER_SIZE = 24;

    /**
     * Size of one slot entry in the slot directory, in bytes.
     *
     * <pre>
     * offset (2 bytes) — short (byte offset of the record within the page)
     * length (2 bytes) — short (byte length of the record)
     * Total: 4 bytes
     * </pre>
     */
    public static final int SLOT_SIZE = 4;

    /**
     * Sentinel value written into a slot's offset field to mark the slot as
     * deleted (tombstone). The slot index is preserved so B+ Tree pointers
     * in M4 remain valid.
     */
    public static final short SLOT_DELETED = -1;

    /**
     * Maximum usable bytes per page after the header.
     * This is the theoretical max; in practice each record also needs
     * a slot entry, so the real max per record is {@code MAX_RECORD_SIZE - SLOT_SIZE}.
     */
    public static final int MAX_RECORD_SIZE = PAGE_SIZE - PAGE_HEADER_SIZE;

    /** File extension for KoraDB table data files. */
    public static final String TABLE_FILE_EXTENSION = ".kdb";

    /** File name for the catalog metadata file within each database directory. */
    public static final String CATALOG_META_FILE = "catalog.meta";

    /** File name for the Write-Ahead Log file within each database directory. */
    public static final String WAL_FILE_NAME = "kora.wal";

    private StorageConfig() {} // utility class — no instances

    /**
     * Returns the byte offset in a table file where page {@code pageId} begins.
     *
     * @param pageId zero-based page number
     * @return byte offset in the file
     */
    public static long pageOffset(int pageId) {
        return (long) pageId * PAGE_SIZE;
    }

    /**
     * Returns the path to the data file for a given table.
     *
     * @param dbDir   the database directory (e.g., {@code data/mydb/})
     * @param tableName the table name
     * @return path like {@code data/mydb/users.kdb}
     */
    public static Path tableFilePath(Path dbDir, String tableName) {
        return dbDir.resolve(tableName.toLowerCase() + TABLE_FILE_EXTENSION);
    }

    /**
     * Returns the path to the Write-Ahead Log (WAL) file for a database directory.
     *
     * @param dbDir the database directory
     * @return path like {@code data/mydb/kora.wal}
     */
    public static Path walFilePath(Path dbDir) {
        return dbDir.resolve(WAL_FILE_NAME);
    }
}
