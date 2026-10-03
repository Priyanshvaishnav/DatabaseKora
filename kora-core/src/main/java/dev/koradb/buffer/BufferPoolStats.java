package dev.koradb.buffer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight counters for buffer pool activity.
 *
 * <p>Collected here so they can be exposed via JMX, Prometheus, or the
 * {@code SHOW BUFFER POOL STATUS} command in Milestone 13 (Observability)
 * without touching the core buffer pool logic.
 *
 * <h2>Counters</h2>
 * <ul>
 *   <li><strong>hits</strong> — page was already in the pool</li>
 *   <li><strong>misses</strong> — page was not in the pool; loaded from disk</li>
 *   <li><strong>evictions</strong> — a frame was evicted to make room</li>
 *   <li><strong>dirtyEvictions</strong> — an evicted frame was dirty (required a disk write)</li>
 * </ul>
 *
 * <h2>Hit ratio</h2>
 * <p>A healthy OLTP workload should maintain a hit ratio above 95%.
 * Low hit ratio means the pool is too small for the working set.
 */
public final class BufferPoolStats {

    private final AtomicLong hits           = new AtomicLong();
    private final AtomicLong misses         = new AtomicLong();
    private final AtomicLong evictions      = new AtomicLong();
    private final AtomicLong dirtyEvictions = new AtomicLong();

    void recordHit()           { hits.incrementAndGet();           }
    void recordMiss()          { misses.incrementAndGet();         }
    void recordEviction()      { evictions.incrementAndGet();      }
    void recordDirtyEviction() { dirtyEvictions.incrementAndGet(); }

    public long getHits()           { return hits.get();           }
    public long getMisses()         { return misses.get();         }
    public long getEvictions()      { return evictions.get();      }
    public long getDirtyEvictions() { return dirtyEvictions.get(); }

    /**
     * Returns the cache hit ratio as a value in [0.0, 1.0].
     * Returns 0.0 if no requests have been made yet.
     */
    public double hitRatio() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0.0 : (double) hits.get() / total;
    }

    public void reset() {
        hits.set(0);
        misses.set(0);
        evictions.set(0);
        dirtyEvictions.set(0);
    }

    @Override
    public String toString() {
        return "BufferPoolStats{hits=%d, misses=%d, hitRatio=%.1f%%, evictions=%d, dirtyEvictions=%d}"
                .formatted(hits.get(), misses.get(), hitRatio() * 100,
                           evictions.get(), dirtyEvictions.get());
    }
}
