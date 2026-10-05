package dev.koradb.storage.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Sequential iterator reading Write-Ahead Log records from a WAL file.
 */
public final class LogIterator implements Iterator<LogRecord>, AutoCloseable {

    private final Path walPath;
    private final FileChannel channel;
    private final ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
    private LogRecord nextRecord = null;
    private long currentPosition;

    public LogIterator(Path walPath) {
        this(walPath, LogManager.WAL_HEADER_SIZE);
    }

    public LogIterator(Path walPath, long startLsn) {
        this.walPath = Objects.requireNonNull(walPath, "walPath must not be null");
        this.currentPosition = Math.max(startLsn, LogManager.WAL_HEADER_SIZE);

        try {
            this.channel = FileChannel.open(walPath, StandardOpenOption.READ);
            advance();
        } catch (IOException e) {
            throw new RuntimeException("Failed to open WAL file for reading: " + walPath, e);
        }
    }

    private void advance() {
        nextRecord = null;
        try {
            if (currentPosition >= channel.size()) {
                return;
            }

            lengthBuffer.clear();
            channel.read(lengthBuffer, currentPosition);
            if (lengthBuffer.position() < 4) {
                return;
            }
            lengthBuffer.flip();
            int totalLength = lengthBuffer.getInt();
            if (totalLength <= 0 || currentPosition + totalLength > channel.size()) {
                return;
            }

            ByteBuffer recordBuffer = ByteBuffer.allocate(totalLength);
            channel.read(recordBuffer, currentPosition);
            recordBuffer.flip();

            nextRecord = LogRecordCodec.decode(recordBuffer);
            currentPosition += totalLength;
        } catch (IOException e) {
            throw new RuntimeException("Error reading WAL record at offset " + currentPosition, e);
        }
    }

    @Override
    public boolean hasNext() {
        return nextRecord != null;
    }

    @Override
    public LogRecord next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        LogRecord result = nextRecord;
        advance();
        return result;
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }
}
