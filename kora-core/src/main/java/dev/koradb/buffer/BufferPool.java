package dev.koradb.buffer;

import dev.koradb.exception.StorageException;
import dev.koradb.storage.DiskPageManager;
import dev.koradb.storage.Page;
import dev.koradb.storage.SlottedPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An LRU page cache that sits between the storage engine and the disk.
 *
 * <p>The buffer pool maintains a fixed number of {@link BufferFrame frames}.
 * Each frame holds one {@link Page}. When a page is requested:
 * <ol>
 *   <li><strong>Hit</strong>: the page is already in a frame — return it
 *       immediately (no disk I/O).</li>
 *   <li><strong>Miss</strong>: the page is not in the pool — find a free
 *       frame (or evict the LRU unpinned frame), load the page from disk,
 *       and return the frame.</li>
 * </ol>
 *
 * <h2>LRU implementation</h2>
 * <p>We use a {@link LinkedHashMap} configured in <em>access order</em>
 * ({@code accessOrder=true}). This means every {@code get()} automatically
 * moves the entry to the tail of the linked list, so the head is always the
 * least-recently-used entry. Eviction is O(1): walk the head until we find
 * an unpinned frame.
 *
 * <h2>Pin / Unpin protocol</h2>
 * <p>Callers <strong>must</strong> follow this protocol:
 * <pre>{@code
 *   BufferFrame frame = pool.pin(pageId);
 *   try {
 *       // read or modify frame.getPage()
 *       pool.markDirty(frame);  // if modified
 *   } finally {
 *       pool.unpin(frame);
 *   }
 * }</pre>
 *
 * <h2>Flush on close</h2>
 * <p>Calling {@link #flushAll()} writes all dirty frames to disk.
 * Calling {@link #close()} flushes then closes the underlying
 * {@link DiskPageManager}.
 *
 * <h2>Thread safety</h2>
 * <p>Not thread-safe in M3. The pool is accessed through a single
 * {@link dev.koradb.storage.TableFile} per table. Per-frame latches will
 * be added in Milestone 11 (concurrency).
 */
public final class BufferPool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BufferPool.class);

    /** Default pool size — 256 pages × 4 KB = 1 MB of page cache. */
    public static final int DEFAULT_POOL_SIZE = 256;

    private final DiskPageManager             diskManager;
    private final int                         capacity;
    private final BufferPoolStats             stats;

    /**
     * LRU map: pageId → frame.
     * LinkedHashMap in access-order mode: most recently used at tail,
     * least recently used at head. Capacity is bounded by {@code capacity}.
     */
    private final LinkedHashMap<Integer, BufferFrame> frames;

    /**
     * Creates a buffer pool of the default size (256 frames = 1 MB).
     *
     * @param diskManager the underlying disk page I/O manager
     */
    public BufferPool(DiskPageManager diskManager) {
        this(diskManager, DEFAULT_POOL_SIZE);
    }

    /**
     * Creates a buffer pool with the given capacity.
     *
     * @param diskManager the underlying disk page I/O manager
     * @param capacity    number of frames (pages) to hold in memory
     */
    public BufferPool(DiskPageManager diskManager, int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException(
                "Buffer pool capacity must be > 0, got: " + capacity);
        this.diskManager = diskManager;
        this.capacity    = capacity;
        this.stats       = new BufferPoolStats();
        // accessOrder=true → get() moves entry to tail (MRU position)
        this.frames      = new LinkedHashMap<>(capacity, 0.75f, true);
        log.debug("BufferPool initialised: capacity={} frames ({} KB)",
                capacity, capacity * 4);
    }

    // =========================================================================
    // Pin (the main operation)
    // =========================================================================

    /**
     * Pins the page with the given ID and returns its frame.
     *
     * <p>If the page is already in the pool (cache hit), its frame is pinned
     * and returned. If not (cache miss), a frame is found via
     * {@link #findOrEvict()} and the page is loaded from disk.
     *
     * @param pageId the page to load (must exist in the table file)
     * @return the frame containing the page
     * @throws StorageException if all frames are pinned (pool exhausted)
     */
    public BufferFrame pin(int pageId) {
        BufferFrame frame = frames.get(pageId);

        if (frame != null) {
            stats.recordHit();
            frame.pin();
            frame.touch();
            log.debug("Buffer HIT  page={}", pageId);
            return frame;
        }

        // Cache miss
        stats.recordMiss();
        log.debug("Buffer MISS page={}", pageId);

        evictIfNeeded();
        Page        page     = diskManager.read(pageId);
        BufferFrame newFrame = new BufferFrame(page);
        frames.put(pageId, newFrame);
        newFrame.pin();
        return newFrame;
    }

    public BufferFrame pinNew(Page page) {
        stats.recordMiss();
        evictIfNeeded();
        BufferFrame newFrame = new BufferFrame(page);
        frames.put(page.getPageId(), newFrame);
        newFrame.pin();
        return newFrame;
    }

    // =========================================================================
    // Unpin
    // =========================================================================

    /**
     * Unpins a frame, allowing it to be evicted if the pool needs space.
     *
     * @param frame the frame to unpin (must have been returned by {@link #pin})
     */
    public void unpin(BufferFrame frame) {
        frame.unpin();
        log.debug("Unpinned page={}, pins={}", frame.getPage().getPageId(), frame.getPinCount());
    }

    // =========================================================================
    // Dirty marking
    // =========================================================================

    /**
     * Marks a frame as dirty — its page will be written to disk before eviction.
     * Must be called after any write to the page in the frame.
     *
     * @param frame the frame whose page was modified
     */
    public void markDirty(BufferFrame frame) {
        frame.markDirty();
        log.debug("Marked dirty: page={}", frame.getPage().getPageId());
    }

    // =========================================================================
    // Flush
    // =========================================================================

    /**
     * Writes all dirty frames to disk without evicting them.
     * Used at shutdown and (in M8) before WAL checkpoints.
     */
    public void flushAll() {
        int flushed = 0;
        for (BufferFrame frame : frames.values()) {
            if (frame.isDirty()) {
                diskManager.write(frame.getPage());
                frame.clearDirty();
                flushed++;
            }
        }
        if (flushed > 0) {
            log.debug("Flushed {} dirty frame(s) to disk", flushed);
        }
    }

    /**
     * Writes a specific page to disk immediately (used for allocate-and-write).
     */
    public void flushPage(int pageId) {
        BufferFrame frame = frames.get(pageId);
        if (frame != null && frame.isDirty()) {
            diskManager.write(frame.getPage());
            frame.clearDirty();
        }
    }

    // =========================================================================
    // Allocate a new page through the pool
    // =========================================================================

    /**
     * Allocates a new page via the disk manager, places it in the pool,
     * and returns the pinned frame.
     *
     * <p>The allocated page is initialised as an empty slotted page.
     *
     * @return the pinned frame containing the new empty page
     */
    public BufferFrame allocatePage() {
        Page newPage = diskManager.allocatePage(); // writes empty page to disk
        return pinNew(newPage);
    }

    // =========================================================================
    // Stats and metadata
    // =========================================================================

    /** Returns the live statistics for this pool. */
    public BufferPoolStats getStats() { return stats; }

    /** Returns the number of frames currently in the pool (≤ capacity). */
    public int size()                  { return frames.size(); }

    /** Returns the maximum number of frames this pool can hold. */
    public int capacity()              { return capacity; }

    /** Returns the underlying disk page manager. */
    public DiskPageManager getDiskManager() { return diskManager; }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Flushes all dirty frames and closes the underlying disk manager.
     * Must be called when the database shuts down.
     */
    @Override
    public void close() {
        flushAll();
        diskManager.close();
        log.debug("BufferPool closed. {}", stats);
    }

    // =========================================================================
    // Private — LRU eviction
    // =========================================================================

    /**
     * If the pool is at capacity, evicts the LRU unpinned frame.
     * Writes the frame to disk first if it is dirty.
     *
     * @throws StorageException if every frame is pinned (pool exhausted)
     */
    private void evictIfNeeded() {
        if (frames.size() < capacity) return; // still room — nothing to do

        // Scan from LRU end (LinkedHashMap iteration order = LRU → MRU)
        Map.Entry<Integer, BufferFrame> victim = null;
        for (Map.Entry<Integer, BufferFrame> entry : frames.entrySet()) {
            if (entry.getValue().isEvictable()) {
                victim = entry;
                break;
            }
        }

        if (victim == null) {
            throw new StorageException(
                    "Buffer pool exhausted: all %d frames are pinned".formatted(capacity));
        }

        BufferFrame evicted = victim.getValue();
        if (evicted.isDirty()) {
            diskManager.write(evicted.getPage());
            stats.recordDirtyEviction();
            log.debug("Evicted dirty page={}", evicted.getPage().getPageId());
        } else {
            log.debug("Evicted clean page={}", evicted.getPage().getPageId());
        }
        stats.recordEviction();
        frames.remove(victim.getKey());
    }
}
