package dev.koradb.benchmarks.btree;

import dev.koradb.buffer.BufferPool;
import dev.koradb.index.BPlusTree;
import dev.koradb.index.IndexKey;
import dev.koradb.sql.ast.DataType;
import dev.koradb.storage.DiskPageManager;
import dev.koradb.storage.RecordId;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JMH Microbenchmark evaluating B+ Tree point lookups, range scans, and insert performance.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class BTreeBenchmark {

    public static final int INITIAL_KEYS = 10_000;

    private Path tempFile;
    private DiskPageManager diskManager;
    private BufferPool bufferPool;
    private BPlusTree btree;
    private final AtomicInteger insertSeq = new AtomicInteger(INITIAL_KEYS + 1);

    @Setup(Level.Trial)
    public void setup() throws IOException {
        tempFile = Files.createTempFile("kora-bpt-bench", ".idx");
        diskManager = new DiskPageManager(tempFile);
        bufferPool = new BufferPool(diskManager, 512);
        btree = new BPlusTree("idx_bench", "accounts", "id", DataType.INT, false, bufferPool);

        for (int i = 1; i <= INITIAL_KEYS; i++) {
            btree.insert(IndexKey.ofInt(i), new RecordId(i / 100, i % 100));
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (bufferPool != null) {
            try {
                bufferPool.close();
            } catch (Exception ignored) {}
        }
        if (diskManager != null) {
            try {
                diskManager.close();
            } catch (Exception ignored) {}
        }
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception ignored) {}
        }
    }

    @Benchmark
    public Optional<RecordId> pointLookupRandom() {
        int target = ThreadLocalRandom.current().nextInt(1, INITIAL_KEYS + 1);
        return btree.search(IndexKey.ofInt(target));
    }

    @Benchmark
    public List<RecordId> rangeScanSmall() {
        int start = ThreadLocalRandom.current().nextInt(1, INITIAL_KEYS - 50);
        return btree.rangeScan(IndexKey.ofInt(start), IndexKey.ofInt(start + 20), true, true);
    }

    @Benchmark
    public List<RecordId> rangeScanMedium() {
        int start = ThreadLocalRandom.current().nextInt(1, INITIAL_KEYS - 250);
        return btree.rangeScan(IndexKey.ofInt(start), IndexKey.ofInt(start + 200), true, true);
    }

    @Benchmark
    public void insertNewKey() {
        int key = insertSeq.getAndIncrement();
        btree.insert(IndexKey.ofInt(key), new RecordId(key / 100, key % 100));
    }
}
