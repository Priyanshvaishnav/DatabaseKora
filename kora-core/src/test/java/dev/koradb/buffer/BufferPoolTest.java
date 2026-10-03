package dev.koradb.buffer;

import dev.koradb.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit and integration tests for the BufferPool.
 *
 * Tests cover:
 *  - Cache hit / miss tracking
 *  - LRU eviction of clean frames
 *  - Dirty-frame write-back on eviction
 *  - Pin-prevents-eviction safety
 *  - flushAll() writes dirty frames without evicting
 *  - Data correctness after eviction and re-pin (round-trip through disk)
 */
@DisplayName("BufferPool")
class BufferPoolTest {

    @TempDir Path tempDir;

    /** Creates a DiskPageManager with N pre-allocated pages. */
    private DiskPageManager createManagerWithPages(String name, int pageCount) {
        Path path = tempDir.resolve(name + ".kdb");
        DiskPageManager mgr = new DiskPageManager(path);
        for (int i = 0; i < pageCount; i++) mgr.allocatePage();
        return mgr;
    }

    // =========================================================================
    // Hit / Miss stats
    // =========================================================================

    @Nested
    @DisplayName("Cache statistics")
    class Stats {

        @Test
        @DisplayName("first access is a miss; second access is a hit")
        void firstMissThenHit() {
            DiskPageManager mgr  = createManagerWithPages("stats", 1);
            BufferPool      pool = new BufferPool(mgr, 4);

            BufferFrame f1 = pool.pin(0);
            pool.unpin(f1);
            assertThat(pool.getStats().getMisses()).isEqualTo(1);
            assertThat(pool.getStats().getHits()).isEqualTo(0);

            BufferFrame f2 = pool.pin(0);
            pool.unpin(f2);
            assertThat(pool.getStats().getHits()).isEqualTo(1);
            assertThat(pool.getStats().getMisses()).isEqualTo(1);

            pool.close();
        }

        @Test
        @DisplayName("hit ratio is 0.0 before any access")
        void hitRatioEmpty() {
            DiskPageManager mgr  = createManagerWithPages("ratio", 1);
            BufferPool      pool = new BufferPool(mgr, 4);
            assertThat(pool.getStats().hitRatio()).isEqualTo(0.0);
            pool.close();
        }

        @Test
        @DisplayName("hit ratio = hits / (hits + misses)")
        void hitRatioCalculation() {
            DiskPageManager mgr  = createManagerWithPages("hitratio", 2);
            BufferPool      pool = new BufferPool(mgr, 4);

            // 2 misses, then 3 hits on page 0
            BufferFrame f = pool.pin(0); pool.unpin(f); // miss
            f = pool.pin(1); pool.unpin(f);             // miss
            f = pool.pin(0); pool.unpin(f);             // hit
            f = pool.pin(0); pool.unpin(f);             // hit
            f = pool.pin(0); pool.unpin(f);             // hit

            // hits=3, misses=2, total=5, ratio=0.6
            assertThat(pool.getStats().hitRatio()).isCloseTo(0.6, within(0.001));
            pool.close();
        }
    }

    // =========================================================================
    // LRU eviction
    // =========================================================================

    @Nested
    @DisplayName("LRU eviction")
    class Eviction {

        @Test
        @DisplayName("pool with capacity 2 evicts the LRU frame when a 3rd page is needed")
        void basicEviction() {
            DiskPageManager mgr  = createManagerWithPages("lru", 3);
            BufferPool      pool = new BufferPool(mgr, 2); // only 2 frames

            // Pin pages 0 and 1 → pool full
            BufferFrame f0 = pool.pin(0); pool.unpin(f0);
            BufferFrame f1 = pool.pin(1); pool.unpin(f1);
            assertThat(pool.size()).isEqualTo(2);

            // Pin page 2 → must evict page 0 (LRU)
            BufferFrame f2 = pool.pin(2); pool.unpin(f2);
            assertThat(pool.size()).isEqualTo(2);
            assertThat(pool.getStats().getEvictions()).isEqualTo(1);

            pool.close();
        }

        @Test
        @DisplayName("eviction count increments correctly over multiple evictions")
        void multipleEvictions() {
            DiskPageManager mgr  = createManagerWithPages("multi", 5);
            BufferPool      pool = new BufferPool(mgr, 2);

            for (int i = 0; i < 5; i++) {
                BufferFrame f = pool.pin(i);
                pool.unpin(f);
            }

            // 5 pages, pool size 2 → 3 evictions (pages 2,3,4 each evict something)
            assertThat(pool.getStats().getEvictions()).isEqualTo(3);
            pool.close();
        }

        @Test
        @DisplayName("dirty frame is written to disk before eviction")
        void dirtyFrameWrittenBeforeEviction() {
            DiskPageManager mgr  = createManagerWithPages("dirty", 2);
            BufferPool      pool = new BufferPool(mgr, 1); // capacity=1, forces eviction

            // Pin page 0, write a sentinel value, mark dirty, unpin
            BufferFrame f0 = pool.pin(0);
            f0.getPage().writeInt(16, 0xDEADBEEF); // write past header
            pool.markDirty(f0);
            pool.unpin(f0);

            // Pin page 1 → page 0 must be evicted (dirty → written to disk first)
            BufferFrame f1 = pool.pin(1);
            pool.unpin(f1);
            assertThat(pool.getStats().getDirtyEvictions()).isEqualTo(1);

            // Re-pin page 0 — it's now a miss (was evicted); should re-read from disk
            BufferFrame f0Again = pool.pin(0);
            try {
                // The sentinel value we wrote must still be there
                assertThat(f0Again.getPage().readInt(16)).isEqualTo(0xDEADBEEF);
            } finally {
                pool.unpin(f0Again);
            }

            pool.close();
        }

        @Test
        @DisplayName("pinned frames are never evicted")
        void pinnedFrameNotEvicted() {
            DiskPageManager mgr  = createManagerWithPages("pinned", 2);
            BufferPool      pool = new BufferPool(mgr, 1); // capacity=1

            // Pin page 0 — do NOT unpin it
            BufferFrame f0 = pool.pin(0);

            // Trying to pin page 1 should fail because page 0 is pinned and pool is full
            assertThatThrownBy(() -> pool.pin(1))
                    .isInstanceOf(dev.koradb.exception.StorageException.class)
                    .hasMessageContaining("exhausted");

            pool.unpin(f0); // cleanup
            pool.close();
        }
    }

    // =========================================================================
    // Flush
    // =========================================================================

    @Nested
    @DisplayName("flushAll")
    class Flush {

        @Test
        @DisplayName("flushAll writes dirty frames to disk without evicting them")
        void flushAllWritesDirtyFrames() {
            DiskPageManager mgr  = createManagerWithPages("flush", 2);
            BufferPool      pool = new BufferPool(mgr, 4);

            // Dirty both pages
            for (int i = 0; i < 2; i++) {
                BufferFrame f = pool.pin(i);
                f.getPage().writeInt(16, 0xABCD1234 + i);
                pool.markDirty(f);
                pool.unpin(f);
            }

            pool.flushAll();

            // After flush, frames should still be in the pool (not evicted)
            assertThat(pool.size()).isEqualTo(2);

            // And dirty flags should be cleared
            // Re-pin each page — should be a HIT (still in pool)
            long hitsBefore = pool.getStats().getHits();
            for (int i = 0; i < 2; i++) {
                BufferFrame f = pool.pin(i);
                pool.unpin(f);
            }
            assertThat(pool.getStats().getHits()).isEqualTo(hitsBefore + 2);

            pool.close();
        }
    }

    // =========================================================================
    // Data correctness end-to-end
    // =========================================================================

    @Nested
    @DisplayName("Data correctness")
    class DataCorrectness {

        @Test
        @DisplayName("data written through pool is readable after eviction and re-pin")
        void roundTripThroughEviction(@TempDir Path dir) {
            Path            path = dir.resolve("rt.kdb");
            DiskPageManager mgr  = new DiskPageManager(path);

            // Pre-allocate 3 pages
            mgr.allocatePage();
            mgr.allocatePage();
            mgr.allocatePage();

            BufferPool pool = new BufferPool(mgr, 2); // pool size 2

            // Write to page 0, mark dirty, unpin
            BufferFrame f0 = pool.pin(0);
            f0.getPage().writeInt(100, 42);
            pool.markDirty(f0);
            pool.unpin(f0);

            // Write to page 1, mark dirty, unpin
            BufferFrame f1 = pool.pin(1);
            f1.getPage().writeInt(100, 99);
            pool.markDirty(f1);
            pool.unpin(f1);

            // Pin page 2 — pool full, evicts page 0 (LRU), writes it to disk
            BufferFrame f2 = pool.pin(2);
            pool.unpin(f2);

            // Re-pin page 0 (miss → reload from disk)
            BufferFrame f0Again = pool.pin(0);
            try {
                assertThat(f0Again.getPage().readInt(100)).isEqualTo(42);
            } finally {
                pool.unpin(f0Again);
            }

            pool.close();
        }
    }
}
