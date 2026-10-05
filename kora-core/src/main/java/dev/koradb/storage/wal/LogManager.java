package dev.koradb.storage.wal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the append-only Write-Ahead Log (WAL) file, in-memory log buffer,
 * LSN generation, and Group Commit disk synchronization.
 */
public final class LogManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LogManager.class);

    public static final int WAL_FILE_MAGIC = 0x4B4F5241; // "KORA"
    public static final int WAL_VERSION = 1;
    public static final int WAL_HEADER_SIZE = 16;
    public static final int DEFAULT_BUFFER_SIZE = 64 * 1024; // 64 KB

    private final Path walPath;
    private final FileChannel channel;
    private final ByteBuffer logBuffer;
    private final AtomicLong nextLsn = new AtomicLong(WAL_HEADER_SIZE);
    private volatile long flushedLsn = WAL_HEADER_SIZE;

    private final Object flushLock = new Object();
    private boolean isFlushing = false;

    public LogManager(Path walPath) {
        this(walPath, DEFAULT_BUFFER_SIZE);
    }

    public LogManager(Path walPath, int bufferSize) {
        this.walPath = Objects.requireNonNull(walPath, "walPath must not be null");
        this.logBuffer = ByteBuffer.allocate(Math.max(bufferSize, 4096));

        try {
            if (walPath.getParent() != null) {
                Files.createDirectories(walPath.getParent());
            }

            this.channel = FileChannel.open(walPath,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE);

            if (channel.size() == 0) {
                // Initialize fresh WAL header
                ByteBuffer header = ByteBuffer.allocate(WAL_HEADER_SIZE);
                header.putInt(WAL_FILE_MAGIC);
                header.putInt(WAL_VERSION);
                header.putLong(0L); // reserved
                header.flip();

                while (header.hasRemaining()) {
                    channel.write(header);
                }
                channel.force(false);
                nextLsn.set(WAL_HEADER_SIZE);
                flushedLsn = WAL_HEADER_SIZE;
            } else {
                // Existing WAL file: validate header
                ByteBuffer header = ByteBuffer.allocate(WAL_HEADER_SIZE);
                channel.read(header, 0);
                header.flip();

                int magic = header.getInt();
                int version = header.getInt();
                if (magic != WAL_FILE_MAGIC || version != WAL_VERSION) {
                    throw new CorruptedLogRecordException(
                            "Invalid WAL header in %s: magic=0x%08X, version=%d"
                                    .formatted(walPath, magic, version));
                }

                long size = channel.size();
                channel.position(size);
                nextLsn.set(size);
                flushedLsn = size;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize LogManager for " + walPath, e);
        }
    }

    public Path getWalPath() {
        return walPath;
    }

    public long getFlushedLsn() {
        return flushedLsn;
    }

    public long getNextLsn() {
        return nextLsn.get();
    }

    /**
     * Appends a log record to the log buffer, assigning it a monotonically increasing LSN.
     *
     * @param record log record to append
     * @return the assigned LSN of the appended record
     */
    public synchronized long append(LogRecord record) {
        Objects.requireNonNull(record, "record must not be null");

        int len = LogRecordCodec.calculateEncodedLength(record);
        if (logBuffer.remaining() < len) {
            flushBufferToChannel();
        }

        long lsn = nextLsn.getAndAdd(len);
        LogRecord stamped = record.withLsn(lsn);
        LogRecordCodec.encode(stamped, logBuffer);
        return lsn;
    }

    public long appendBegin(long txnId, long prevLsn) {
        return append(new BeginLogRecord(prevLsn, txnId));
    }

    public long appendCommit(long txnId, long prevLsn) {
        return append(new CommitLogRecord(prevLsn, txnId));
    }

    public long appendAbort(long txnId, long prevLsn) {
        return append(new AbortLogRecord(prevLsn, txnId));
    }

    public long appendUpdate(long txnId, long prevLsn, String tableName, int pageId, int slotIndex,
                             byte[] beforeImage, byte[] afterImage) {
        return append(new UpdateLogRecord(prevLsn, txnId, tableName, pageId, slotIndex, beforeImage, afterImage));
    }

    public long appendCheckpointBegin(long timestamp) {
        return append(new CheckpointBeginRecord(timestamp));
    }

    public long appendCheckpointEnd(Map<Long, Long> activeTxns, Map<Integer, Long> dirtyPages) {
        return append(new CheckpointEndRecord(activeTxns, dirtyPages));
    }

    /**
     * Synchronously flushes log records up to {@code targetLsn} to disk using Group Commit.
     *
     * <p>If multiple threads call {@code flush()} concurrently, one thread executes the
     * physical I/O and {@code fsync()}, while other waiting threads are unblocked as part
     * of the same batch.
     */
    public void flush(long targetLsn) {
        if (targetLsn < flushedLsn) {
            return;
        }

        synchronized (flushLock) {
            if (targetLsn < flushedLsn) {
                return;
            }
            while (isFlushing) {
                try {
                    flushLock.wait();
                    if (targetLsn < flushedLsn) {
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for WAL flush", e);
                }
            }
            isFlushing = true;
        }

        try {
            long currentNextLsn;
            synchronized (this) {
                flushBufferToChannel();
                currentNextLsn = nextLsn.get();
            }

            channel.force(false); // fsync

            synchronized (flushLock) {
                flushedLsn = currentNextLsn;
            }
        } catch (IOException e) {
            throw new RuntimeException("Error flushing WAL channel to disk", e);
        } finally {
            synchronized (flushLock) {
                isFlushing = false;
                flushLock.notifyAll();
            }
        }
    }

    /**
     * Flushes all currently buffered records to disk.
     */
    public void flushAll() {
        flush(nextLsn.get());
    }

    private synchronized void flushBufferToChannel() {
        if (logBuffer.position() > 0) {
            logBuffer.flip();
            try {
                while (logBuffer.hasRemaining()) {
                    channel.write(logBuffer);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed writing WAL buffer to channel", e);
            }
            logBuffer.clear();
        }
    }

    @Override
    public synchronized void close() {
        try {
            flushAll();
            channel.close();
            log.debug("Closed LogManager for {}", walPath);
        } catch (IOException e) {
            log.warn("Error closing LogManager for {}", walPath, e);
        }
    }
}
