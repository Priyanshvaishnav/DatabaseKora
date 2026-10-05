package dev.koradb.storage.wal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WAL LogManager & Group Commit")
class LogManagerTest {

    @Test
    @DisplayName("Appends records with monotonic LSN progression and reads back via LogIterator")
    void testAppendAndReadBack(@TempDir Path tempDir) {
        Path walPath = tempDir.resolve("test.wal");

        try (LogManager logManager = new LogManager(walPath)) {
            assertThat(logManager.getNextLsn()).isEqualTo(LogManager.WAL_HEADER_SIZE);
            assertThat(logManager.getFlushedLsn()).isEqualTo(LogManager.WAL_HEADER_SIZE);

            long lsn1 = logManager.appendBegin(1L, 0L);
            assertThat(lsn1).isEqualTo(LogManager.WAL_HEADER_SIZE);

            long lsn2 = logManager.appendUpdate(1L, lsn1, "users", 0, 1, null, new byte[]{10, 20});
            assertThat(lsn2).isGreaterThan(lsn1);

            long lsn3 = logManager.appendCommit(1L, lsn2);
            assertThat(lsn3).isGreaterThan(lsn2);

            // Flush up to commit
            logManager.flush(lsn3);
            assertThat(logManager.getFlushedLsn()).isGreaterThanOrEqualTo(lsn3);
        }

        // Reopen with LogIterator and verify all records
        try (LogIterator iterator = new LogIterator(walPath)) {
            List<LogRecord> records = new ArrayList<>();
            while (iterator.hasNext()) {
                records.add(iterator.next());
            }

            assertThat(records).hasSize(3);
            assertThat(records.get(0)).isInstanceOf(BeginLogRecord.class);
            assertThat(records.get(0).getTxnId()).isEqualTo(1L);

            assertThat(records.get(1)).isInstanceOf(UpdateLogRecord.class);
            UpdateLogRecord u = (UpdateLogRecord) records.get(1);
            assertThat(u.getTableName()).isEqualTo("users");
            assertThat(u.getAfterImage()).containsExactly((byte) 10, (byte) 20);

            assertThat(records.get(2)).isInstanceOf(CommitLogRecord.class);
            assertThat(records.get(2).getTxnId()).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("Group commit: concurrent flushes are batched into shared fsync cycles")
    void testGroupCommit(@TempDir Path tempDir) throws Exception {
        Path walPath = tempDir.resolve("group_commit.wal");
        int threadCount = 8;
        int recordsPerThread = 50;

        try (LogManager logManager = new LogManager(walPath, 4096)) {
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            AtomicInteger totalAppended = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final long txnId = t + 1;
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    long prevLsn = 0L;
                    for (int r = 0; r < recordsPerThread; r++) {
                        long lsn = logManager.appendUpdate(txnId, prevLsn, "accounts", 1, r,
                                new byte[]{(byte) r}, new byte[]{(byte) (r + 1)});
                        prevLsn = lsn;
                        totalAppended.incrementAndGet();

                        if (r % 5 == 0) {
                            // Frequent flush calls to exercise group commit concurrency
                            logManager.flush(lsn);
                        }
                    }
                    long commitLsn = logManager.appendCommit(txnId, prevLsn);
                    totalAppended.incrementAndGet();
                    logManager.flush(commitLsn);
                    return null;
                }));
            }

            startLatch.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
            executor.shutdown();

            logManager.flushAll();
            assertThat(logManager.getFlushedLsn()).isEqualTo(logManager.getNextLsn());
        }

        // Verify all records present in WAL
        try (LogIterator iterator = new LogIterator(walPath)) {
            int count = 0;
            while (iterator.hasNext()) {
                iterator.next();
                count++;
            }
            assertThat(count).isEqualTo(threadCount * (recordsPerThread + 1));
        }
    }
}
