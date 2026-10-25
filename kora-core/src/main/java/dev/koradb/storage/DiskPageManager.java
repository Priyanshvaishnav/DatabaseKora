package dev.koradb.storage;

import dev.koradb.exception.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/**
 * Reads and writes {@link Page}s to/from a single file on disk.
 *
 * <h2>File format</h2>
 * <p>The file is a flat array of fixed-size pages:
 * <pre>
 * Byte offset 0         : Page 0 (4096 bytes)
 * Byte offset 4096      : Page 1 (4096 bytes)
 * Byte offset 4096 * N  : Page N (4096 bytes)
 * </pre>
 *
 * <h2>I/O strategy (Milestone 2)</h2>
 * <p>Every {@link #read} and {@link #write} goes directly to disk via
 * {@link RandomAccessFile}. There is no page cache here — that is
 * Milestone 3 (Buffer Pool). {@link #write} calls {@code fd.getFD().sync()}
 * only when explicitly asked; by default it relies on the OS write-back cache
 * for development-time speed.
 *
 * <h2>Thread safety</h2>
 * <p>Not thread-safe. The buffer pool (M3) serializes page I/O.
 *
 * <h2>Lifecycle</h2>
 * <p>Must be {@link #close()}d when the database shuts down to release the
 * file descriptor. Implements {@link AutoCloseable} so it works in
 * try-with-resources.
 */
public final class DiskPageManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DiskPageManager.class);

    private final Path             filePath;
    private final RandomAccessFile file;

    /**
     * Opens (or creates) the table file at the given path.
     *
     * @param filePath path to the {@code .kdb} table file
     * @throws StorageException if the file cannot be opened
     */
    public DiskPageManager(Path filePath) {
        this.filePath = filePath;
        try {
            this.file = new RandomAccessFile(filePath.toFile(), "rw");
            log.debug("Opened table file: {}", filePath);
        } catch (IOException e) {
            throw new StorageException(
                    "Cannot open table file: " + filePath, e);
        }
    }

    // =========================================================================
    // Read
    // =========================================================================

    /**
     * Reads page {@code pageId} from disk into a new {@link Page} object.
     *
     * @param pageId zero-based page index
     * @return the page with its data populated
     * @throws StorageException if the page does not exist or an I/O error occurs
     */
    public synchronized Page read(int pageId) {
        long offset = StorageConfig.pageOffset(pageId);
        checkPageExists(pageId, offset);

        byte[] data = new byte[StorageConfig.PAGE_SIZE];
        try {
            file.seek(offset);
            int bytesRead = file.read(data);
            if (bytesRead != StorageConfig.PAGE_SIZE) {
                throw new StorageException(
                        "Partial page read: expected %d bytes but got %d for page %d"
                                .formatted(StorageConfig.PAGE_SIZE, bytesRead, pageId));
            }
        } catch (IOException e) {
            throw new StorageException("I/O error reading page %d".formatted(pageId), e);
        }

        log.debug("Read page {} from {}", pageId, filePath.getFileName());
        return new Page(pageId, data);
    }

    // =========================================================================
    // Write
    // =========================================================================

    /**
     * Writes a {@link Page} to disk at its correct file offset.
     *
     * <p>The page data is written in a single {@code write()} call. The OS
     * write-back cache may defer the actual disk write. Call {@link #flush()}
     * to force the data to durable storage (used by WAL in M8).
     *
     * @param page the page to write (must have a valid page ID)
     * @throws StorageException if an I/O error occurs
     */
    public synchronized void write(Page page) {
        long offset = StorageConfig.pageOffset(page.getPageId());
        try {
            file.seek(offset);
            file.write(page.getData());
        } catch (IOException e) {
            throw new StorageException(
                    "I/O error writing page %d".formatted(page.getPageId()), e);
        }
        log.debug("Wrote page {} to {}", page.getPageId(), filePath.getFileName());
    }

    // =========================================================================
    // Allocation
    // =========================================================================

    /**
     * Allocates a new page at the end of the file and returns it, initialised
     * as an empty slotted page.
     *
     * @return the newly allocated and initialised {@link Page}
     * @throws StorageException if an I/O error occurs
     */
    public synchronized Page allocatePage() {
        int newPageId = pageCount();
        Page page     = new Page(newPageId);

        // Initialise as an empty slotted page
        SlottedPage sp = new SlottedPage(page);
        sp.initialise();

        // Write it to disk immediately to extend the file
        write(page);
        log.debug("Allocated page {} in {}", newPageId, filePath.getFileName());
        return page;
    }

    // =========================================================================
    // Metadata
    // =========================================================================

    /**
     * Returns the total number of pages currently in the file.
     *
     * @throws StorageException if an I/O error occurs
     */
    public synchronized int pageCount() {
        try {
            long length = file.length();
            return (int)(length / StorageConfig.PAGE_SIZE);
        } catch (IOException e) {
            throw new StorageException("Cannot determine file length: " + filePath, e);
        }
    }

    /**
     * Forces all pending writes to durable storage (fsync).
     * Called by the WAL in Milestone 8 before acknowledging a commit.
     */
    public synchronized void flush() {
        try {
            file.getFD().sync();
        } catch (IOException e) {
            throw new StorageException("fsync failed for: " + filePath, e);
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public synchronized void close() {
        try {
            file.close();
            log.debug("Closed table file: {}", filePath);
        } catch (IOException e) {
            log.warn("Failed to close table file: {}", filePath, e);
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void checkPageExists(int pageId, long offset) {
        try {
            long length = file.length();
            if (offset + StorageConfig.PAGE_SIZE > length) {
                throw new StorageException(
                        "Page %d does not exist in file %s (file size: %d bytes)"
                                .formatted(pageId, filePath.getFileName(), length));
            }
        } catch (IOException e) {
            throw new StorageException("Cannot check file length: " + filePath, e);
        }
    }
}
