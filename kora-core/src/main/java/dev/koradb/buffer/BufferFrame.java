package dev.koradb.buffer;

import dev.koradb.storage.Page;

/**
 * A single slot in the {@link BufferPool}.
 *
 * <p>A frame holds one {@link Page} plus the metadata the buffer pool needs
 * to manage it: how many operations are currently using it (pin count),
 * whether it has been modified (dirty), and when it was last accessed
 * (for LRU eviction).
 *
 * <h2>Pin count</h2>
 * <p>While a frame's pin count is greater than zero the buffer pool will
 * never evict it. Callers must call {@link BufferPool#unpin(BufferFrame)}
 * when they are done with a frame, otherwise the pool will eventually run
 * out of evictable frames and throw.
 *
 * <h2>Dirty flag</h2>
 * <p>Marked by the caller via {@link BufferPool#markDirty(BufferFrame)} after
 * modifying the page. When the frame is evicted the pool writes the page to
 * disk if and only if the dirty flag is set.
 *
 * <h2>Thread safety</h2>
 * <p>Not thread-safe in M3. Per-frame latches (read-write locks) will be
 * added in M11 (concurrency).
 */
public final class BufferFrame {

    private final Page page;
    private int     pinCount;
    private boolean dirty;
    private long    lastAccessTime; // System.nanoTime() — used for LRU ordering

    BufferFrame(Page page) {
        this.page           = page;
        this.pinCount       = 0;
        this.dirty          = false;
        this.lastAccessTime = System.nanoTime();
    }

    // =========================================================================
    // Accessors (package-private setters — only BufferPool mutates these)
    // =========================================================================

    public Page getPage()          { return page;       }
    public int  getPinCount()      { return pinCount;   }
    public boolean isDirty()       { return dirty;      }
    public long getLastAccessTime(){ return lastAccessTime; }

    /** Returns true if this frame can be evicted (no active users). */
    public boolean isEvictable()   { return pinCount == 0; }

    // Package-private mutations — called only by BufferPool
    void pin()               { pinCount++;                           }
    void unpin()             { if (pinCount > 0) pinCount--;         }
    void markDirty()         { dirty = true;                         }
    void clearDirty()        { dirty = false;                        }
    void touch()             { lastAccessTime = System.nanoTime();   }

    @Override
    public String toString() {
        return "Frame{pageId=%d, pins=%d, dirty=%b}"
                .formatted(page.getPageId(), pinCount, dirty);
    }
}
