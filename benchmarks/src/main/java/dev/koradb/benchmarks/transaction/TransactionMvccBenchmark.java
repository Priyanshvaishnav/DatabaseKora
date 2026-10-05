package dev.koradb.benchmarks.transaction;

import dev.koradb.KoraDB;
import dev.koradb.execution.QueryResult;
import dev.koradb.session.Session;
import dev.koradb.storage.wal.CommitLogRecord;
import dev.koradb.storage.wal.LogManager;
import dev.koradb.storage.wal.LogRecord;
import dev.koradb.storage.wal.LogRecordCodec;
import dev.koradb.transaction.IsolationLevel;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMH Microbenchmark evaluating MVCC snapshot reads, short ACID transactions,
 * and Write-Ahead Log record encoding/append throughput.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class TransactionMvccBenchmark {

    public static final int ACCOUNT_COUNT = 100;

    private Path tempDir;
    private KoraDB db;
    private Path walPath;
    private LogManager logManager;
    private final AtomicLong txnIdGen = new AtomicLong(1000);
    private ByteBuffer encodedCommitBuffer;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        tempDir = Files.createTempDirectory("kora-tx-bench");
        db = new KoraDB(tempDir);

        db.execute("CREATE DATABASE bank");
        db.execute("USE bank");
        db.execute("CREATE TABLE accounts (id INT, balance DOUBLE)");

        for (int i = 1; i <= ACCOUNT_COUNT; i++) {
            db.execute(String.format("INSERT INTO accounts VALUES (%d, %.2f)", i, 1000.0));
        }

        walPath = tempDir.resolve("bench.wal");
        logManager = new LogManager(walPath);

        // Pre-encode a commit log record for decoding benchmark
        CommitLogRecord sampleRecord = new CommitLogRecord(100, 50, 1001);
        encodedCommitBuffer = ByteBuffer.allocate(64);
        LogRecordCodec.encode(sampleRecord, encodedCommitBuffer);
        encodedCommitBuffer.flip();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (logManager != null) {
            try {
                logManager.close();
            } catch (Exception ignored) {}
        }
        if (db != null) {
            try {
                db.close();
            } catch (Exception ignored) {}
        }
        if (tempDir != null) {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }

    @Benchmark
    public QueryResult snapshotRead() {
        Session session = db.createSession();
        session.setDefaultIsolationLevel(IsolationLevel.SNAPSHOT);
        int target = ThreadLocalRandom.current().nextInt(1, ACCOUNT_COUNT + 1);
        return db.execute(session, "SELECT * FROM accounts WHERE id = " + target);
    }

    @Benchmark
    public void shortTransaction() {
        Session session = db.createSession();
        session.setDefaultIsolationLevel(IsolationLevel.SNAPSHOT);
        int target = ThreadLocalRandom.current().nextInt(1, ACCOUNT_COUNT + 1);
        db.execute(session, "BEGIN");
        db.execute(session, "UPDATE accounts SET balance = 1500.0 WHERE id = " + target);
        db.execute(session, "COMMIT");
    }

    @Benchmark
    public long walAppendRecord() {
        long txnId = txnIdGen.incrementAndGet();
        CommitLogRecord record = new CommitLogRecord(0, 0, txnId);
        return logManager.append(record);
    }

    @Benchmark
    public ByteBuffer logRecordEncode() {
        ByteBuffer buf = ByteBuffer.allocate(64);
        CommitLogRecord record = new CommitLogRecord(100, 50, 42);
        LogRecordCodec.encode(record, buf);
        return buf;
    }

    @Benchmark
    public LogRecord logRecordDecode() {
        ByteBuffer slice = encodedCommitBuffer.slice();
        return LogRecordCodec.decode(slice);
    }
}
