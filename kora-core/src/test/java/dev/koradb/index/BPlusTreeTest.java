package dev.koradb.index;

import dev.koradb.buffer.BufferPool;
import dev.koradb.exception.StorageException;
import dev.koradb.sql.ast.DataType;
import dev.koradb.storage.DiskPageManager;
import dev.koradb.storage.RecordId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BPlusTree")
class BPlusTreeTest {

    @TempDir
    Path tempDir;

    private Path indexPath;
    private DiskPageManager diskManager;
    private BufferPool bufferPool;

    @BeforeEach
    void setUp() {
        indexPath = tempDir.resolve("users_id.idx");
        diskManager = new DiskPageManager(indexPath);
        bufferPool = new BufferPool(diskManager);
    }

    @AfterEach
    void tearDown() {
        if (bufferPool != null) {
            bufferPool.close();
        }
    }

    @Nested
    @DisplayName("Basic Operations")
    class BasicOperations {

        @Test
        @DisplayName("Inserts and searches keys in a single leaf")
        void insertAndSearchSingleLeaf() {
            BPlusTree tree = new BPlusTree("idx_users_id", "users", "id", DataType.INT, false, bufferPool, 10);

            tree.insert(IndexKey.ofInt(10), new RecordId(1, 0));
            tree.insert(IndexKey.ofInt(20), new RecordId(1, 1));
            tree.insert(IndexKey.ofInt(5), new RecordId(1, 2));

            assertThat(tree.size()).isEqualTo(3);
            assertThat(tree.search(IndexKey.ofInt(10))).contains(new RecordId(1, 0));
            assertThat(tree.search(IndexKey.ofInt(20))).contains(new RecordId(1, 1));
            assertThat(tree.search(IndexKey.ofInt(5))).contains(new RecordId(1, 2));
            assertThat(tree.search(IndexKey.ofInt(99))).isEmpty();
        }

        @Test
        @DisplayName("Enforces uniqueness when unique=true")
        void enforcesUniqueness() {
            BPlusTree tree = new BPlusTree("idx_users_id", "users", "id", DataType.INT, true, bufferPool, 10);

            tree.insert(IndexKey.ofInt(42), new RecordId(1, 0));

            assertThatThrownBy(() -> tree.insert(IndexKey.ofInt(42), new RecordId(1, 1)))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Unique index violation");
        }

        @Test
        @DisplayName("Deletes key from leaf")
        void deleteKey() {
            BPlusTree tree = new BPlusTree("idx_users_id", "users", "id", DataType.INT, false, bufferPool, 10);

            tree.insert(IndexKey.ofInt(10), new RecordId(1, 0));
            tree.insert(IndexKey.ofInt(20), new RecordId(1, 1));

            assertThat(tree.delete(IndexKey.ofInt(10), new RecordId(1, 0))).isTrue();
            assertThat(tree.search(IndexKey.ofInt(10))).isEmpty();
            assertThat(tree.search(IndexKey.ofInt(20))).contains(new RecordId(1, 1));
            assertThat(tree.size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Splits and Tree Growth")
    class SplitsAndGrowth {

        @Test
        @DisplayName("Splits leaf when exceeding maxDegree and creates internal root")
        void leafSplitCreatesRoot() {
            // maxDegree = 3: after inserting 4 keys, leaf must split
            BPlusTree tree = new BPlusTree("idx_users_id", "users", "id", DataType.INT, false, bufferPool, 3);

            tree.insert(IndexKey.ofInt(10), new RecordId(1, 0));
            tree.insert(IndexKey.ofInt(20), new RecordId(1, 1));
            tree.insert(IndexKey.ofInt(30), new RecordId(1, 2));
            tree.insert(IndexKey.ofInt(40), new RecordId(1, 3));

            assertThat(tree.size()).isEqualTo(4);

            // All keys must still be retrievable
            assertThat(tree.search(IndexKey.ofInt(10))).contains(new RecordId(1, 0));
            assertThat(tree.search(IndexKey.ofInt(20))).contains(new RecordId(1, 1));
            assertThat(tree.search(IndexKey.ofInt(30))).contains(new RecordId(1, 2));
            assertThat(tree.search(IndexKey.ofInt(40))).contains(new RecordId(1, 3));
        }

        @Test
        @DisplayName("Handles multiple leaf splits and internal splits")
        void multiLevelSplits() {
            BPlusTree tree = new BPlusTree("idx_users_id", "users", "id", DataType.INT, false, bufferPool, 3);

            int n = 50;
            for (int i = 1; i <= n; i++) {
                tree.insert(IndexKey.ofInt(i * 10), new RecordId(i, i % 5));
            }

            assertThat(tree.size()).isEqualTo(n);

            // Verify all keys can be searched
            for (int i = 1; i <= n; i++) {
                Optional<RecordId> found = tree.search(IndexKey.ofInt(i * 10));
                assertThat(found)
                        .as("Search key %d", i * 10)
                        .contains(new RecordId(i, i % 5));
            }
        }
    }

    @Nested
    @DisplayName("Range Scans")
    class RangeScans {

        @Test
        @DisplayName("Performs bounded inclusive and exclusive range scans across multiple leaves")
        void boundedRangeScan() {
            BPlusTree tree = new BPlusTree("idx_users_id", "users", "id", DataType.INT, false, bufferPool, 3);

            for (int i = 1; i <= 20; i++) {
                tree.insert(IndexKey.ofInt(i), new RecordId(1, i));
            }

            // Inclusive: [5, 10]
            List<RecordId> inclusive = tree.rangeScan(IndexKey.ofInt(5), IndexKey.ofInt(10), true, true);
            assertThat(inclusive).hasSize(6);
            assertThat(inclusive.get(0)).isEqualTo(new RecordId(1, 5));
            assertThat(inclusive.get(5)).isEqualTo(new RecordId(1, 10));

            // Exclusive: (5, 10)
            List<RecordId> exclusive = tree.rangeScan(IndexKey.ofInt(5), IndexKey.ofInt(10), false, false);
            assertThat(exclusive).hasSize(4);
            assertThat(exclusive.get(0)).isEqualTo(new RecordId(1, 6));
            assertThat(exclusive.get(3)).isEqualTo(new RecordId(1, 9));
        }

        @Test
        @DisplayName("Performs unbounded range scan (low=null, high=null)")
        void unboundedRangeScan() {
            BPlusTree tree = new BPlusTree("idx_users_id", "users", "id", DataType.INT, false, bufferPool, 3);

            for (int i = 1; i <= 15; i++) {
                tree.insert(IndexKey.ofInt(i), new RecordId(1, i));
            }

            // Unbounded low: <= 5
            List<RecordId> lowUnbounded = tree.rangeScan(null, IndexKey.ofInt(5), false, true);
            assertThat(lowUnbounded).hasSize(5);

            // Unbounded high: >= 11
            List<RecordId> highUnbounded = tree.rangeScan(IndexKey.ofInt(11), null, true, false);
            assertThat(highUnbounded).hasSize(5);

            // Full scan: null to null
            List<RecordId> all = tree.rangeScan(null, null, false, false);
            assertThat(all).hasSize(15);
        }
    }

    @Nested
    @DisplayName("Durability and Restart Persistence")
    class Persistence {

        @Test
        @DisplayName("Reopens existing index file and preserves all data")
        void reopenPersistence() {
            BPlusTree tree = new BPlusTree("idx_users_id", "users", "id", DataType.INT, false, bufferPool, 3);

            for (int i = 1; i <= 30; i++) {
                tree.insert(IndexKey.ofInt(i * 2), new RecordId(i, 0));
            }

            // Close the tree and flush buffer pool
            tree.close();

            // Reopen using a new DiskPageManager and BufferPool
            DiskPageManager newDiskManager = new DiskPageManager(indexPath);
            BufferPool newBufferPool = new BufferPool(newDiskManager);

            try {
                BPlusTree reopenedTree = new BPlusTree("idx_users_id", "users", "id", DataType.INT, false, newBufferPool, 3);

                assertThat(reopenedTree.size()).isEqualTo(30);

                // Verify point lookups
                for (int i = 1; i <= 30; i++) {
                    assertThat(reopenedTree.search(IndexKey.ofInt(i * 2)))
                            .contains(new RecordId(i, 0));
                }

                // Verify range scan on reopened tree
                List<RecordId> range = reopenedTree.rangeScan(IndexKey.ofInt(10), IndexKey.ofInt(20), true, true);
                assertThat(range).hasSize(6); // 10, 12, 14, 16, 18, 20
            } finally {
                newBufferPool.close();
            }
        }
    }

    @Nested
    @DisplayName("Large Volume & Multi-Types")
    class LargeVolumeAndTypes {

        @Test
        @DisplayName("Handles 1000 insertions into deep tree and verifies every key")
        void largeVolumeInteger() {
            BPlusTree tree = new BPlusTree("idx_large", "large", "id", DataType.INT, false, bufferPool, 4);

            int total = 1000;
            for (int i = 1; i <= total; i++) {
                tree.insert(IndexKey.ofInt(i), new RecordId(i, i % 10));
            }

            assertThat(tree.size()).isEqualTo(total);

            // Point lookup for all 1000 keys
            for (int i = 1; i <= total; i++) {
                assertThat(tree.search(IndexKey.ofInt(i)))
                        .contains(new RecordId(i, i % 10));
            }

            // Range scan: 250 to 750
            List<RecordId> range = tree.rangeScan(IndexKey.ofInt(250), IndexKey.ofInt(750), true, true);
            assertThat(range).hasSize(501);
            assertThat(range.get(0)).isEqualTo(new RecordId(250, 250 % 10));
            assertThat(range.get(500)).isEqualTo(new RecordId(750, 750 % 10));
        }

        @Test
        @DisplayName("Indexes VARCHAR string keys and performs prefix range scans")
        void stringIndex() {
            BPlusTree tree = new BPlusTree("idx_users_name", "users", "name", DataType.VARCHAR, false, bufferPool, 4);

            String[] names = {"alice", "bob", "charlie", "david", "emma", "frank", "grace", "heidi"};
            for (int i = 0; i < names.length; i++) {
                tree.insert(IndexKey.ofString(names[i]), new RecordId(1, i));
            }

            assertThat(tree.size()).isEqualTo(names.length);

            // Point lookup
            assertThat(tree.search(IndexKey.ofString("charlie"))).contains(new RecordId(1, 2));
            assertThat(tree.search(IndexKey.ofString("unknown"))).isEmpty();

            // Range scan: "bob" to "emma" inclusive
            List<RecordId> range = tree.rangeScan(IndexKey.ofString("bob"), IndexKey.ofString("emma"), true, true);
            assertThat(range).hasSize(4); // bob, charlie, david, emma
            assertThat(range.get(0)).isEqualTo(new RecordId(1, 1));
            assertThat(range.get(3)).isEqualTo(new RecordId(1, 4));
        }

        @Test
        @DisplayName("Supports duplicate keys in non-unique index")
        void duplicateKeys() {
            BPlusTree tree = new BPlusTree("idx_orders_user_id", "orders", "user_id", DataType.INT, false, bufferPool, 4);

            tree.insert(IndexKey.ofInt(100), new RecordId(1, 0));
            tree.insert(IndexKey.ofInt(100), new RecordId(1, 1));
            tree.insert(IndexKey.ofInt(100), new RecordId(1, 2));
            tree.insert(IndexKey.ofInt(200), new RecordId(2, 0));

            assertThat(tree.size()).isEqualTo(4);

            List<RecordId> forUser100 = tree.searchAll(IndexKey.ofInt(100));
            assertThat(forUser100).containsExactlyInAnyOrder(
                    new RecordId(1, 0),
                    new RecordId(1, 1),
                    new RecordId(1, 2)
            );
        }
    }
}
