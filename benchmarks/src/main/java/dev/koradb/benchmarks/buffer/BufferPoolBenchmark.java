package dev.koradb.benchmarks.buffer;

import dev.koradb.buffer.BufferFrame;
import dev.koradb.buffer.BufferPool;
import dev.koradb.storage.DiskPageManager;
import dev.koradb.storage.Page;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * JMH Microbenchmark evaluating Buffer Pool page caching, LRU replacement, and dirty writes.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class BufferPoolBenchmark {

    public static final int TOTAL_PAGES = 500;
    public static final int HOT_PAGES = 50;

    private Path tempFile;
    private DiskPageManager diskManager;
    private BufferPool bufferPool;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        tempFile = Files.createTempFile("kora-bp-bench", ".db");
        diskManager = new DiskPageManager(tempFile);

        // Pre-allocate pages on disk
        for (int i = 0; i < TOTAL_PAGES; i++) {
            diskManager.write(new Page(i));
        }

        // 128-frame capacity pool
        bufferPool = new BufferPool(diskManager, 128);

        // Warm up hot pages into memory
        for (int i = 0; i < HOT_PAGES; i++) {
            BufferFrame frame = bufferPool.pin(i);
            bufferPool.unpin(frame);
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
    public byte cacheHitRead() {
        int pageId = ThreadLocalRandom.current().nextInt(0, HOT_PAGES);
        BufferFrame frame = bufferPool.pin(pageId);
        try {
            return frame.getPage().getData()[0];
        } finally {
            bufferPool.unpin(frame);
        }
    }

    @Benchmark
    public void cacheHitDirtyWrite() {
        int pageId = ThreadLocalRandom.current().nextInt(0, HOT_PAGES);
        BufferFrame frame = bufferPool.pin(pageId);
        try {
            bufferPool.markDirty(frame);
            frame.getPage().getData()[0] = 0x42;
        } finally {
            bufferPool.unpin(frame);
        }
    }

    @Benchmark
    public byte lruEvictionCycle() {
        // Access pages across the full 500 page range to trigger LRU evictions
        int pageId = ThreadLocalRandom.current().nextInt(0, TOTAL_PAGES);
        BufferFrame frame = bufferPool.pin(pageId);
        try {
            return frame.getPage().getData()[0];
        } finally {
            bufferPool.unpin(frame);
        }
    }
}
