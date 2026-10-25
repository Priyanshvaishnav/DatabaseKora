package dev.koradb.storage.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Random-access reader for single WAL records (used by runtime rollback). */
public final class WalReader implements AutoCloseable {
    private final FileChannel channel;

    public WalReader(Path walPath) throws IOException {
        this.channel = FileChannel.open(walPath, StandardOpenOption.READ);
    }

    public LogRecord readAt(long lsn) throws IOException {
        ByteBuffer lenBuf = ByteBuffer.allocate(4);
        channel.read(lenBuf, lsn);
        lenBuf.flip();
        int totalLength = lenBuf.getInt();
        ByteBuffer recordBuf = ByteBuffer.allocate(totalLength);
        channel.read(recordBuf, lsn);
        recordBuf.flip();
        return LogRecordCodec.decode(recordBuf);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
