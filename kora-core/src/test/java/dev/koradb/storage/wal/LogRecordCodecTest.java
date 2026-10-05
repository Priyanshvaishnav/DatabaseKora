package dev.koradb.storage.wal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WAL LogRecordCodec Binary Serialization")
class LogRecordCodecTest {

    @Test
    @DisplayName("Encodes and decodes BeginLogRecord")
    void testBeginRecord() {
        BeginLogRecord record = new BeginLogRecord(100L, 0L, 42L);
        ByteBuffer buffer = ByteBuffer.allocate(128);

        LogRecordCodec.encode(record, buffer);
        buffer.flip();

        LogRecord decoded = LogRecordCodec.decode(buffer);
        assertThat(decoded).isInstanceOf(BeginLogRecord.class);
        assertThat(decoded.getLsn()).isEqualTo(100L);
        assertThat(decoded.getPrevLsn()).isEqualTo(0L);
        assertThat(decoded.getTxnId()).isEqualTo(42L);
        assertThat(decoded.getType()).isEqualTo(LogRecordType.BEGIN);
    }

    @Test
    @DisplayName("Encodes and decodes CommitLogRecord and AbortLogRecord")
    void testCommitAndAbortRecords() {
        CommitLogRecord commit = new CommitLogRecord(250L, 100L, 42L);
        ByteBuffer buffer = ByteBuffer.allocate(128);

        LogRecordCodec.encode(commit, buffer);
        buffer.flip();

        LogRecord decodedCommit = LogRecordCodec.decode(buffer);
        assertThat(decodedCommit).isInstanceOf(CommitLogRecord.class);
        assertThat(decodedCommit.getLsn()).isEqualTo(250L);
        assertThat(decodedCommit.getTxnId()).isEqualTo(42L);

        AbortLogRecord abort = new AbortLogRecord(300L, 100L, 43L);
        buffer.clear();
        LogRecordCodec.encode(abort, buffer);
        buffer.flip();

        LogRecord decodedAbort = LogRecordCodec.decode(buffer);
        assertThat(decodedAbort).isInstanceOf(AbortLogRecord.class);
        assertThat(decodedAbort.getLsn()).isEqualTo(300L);
        assertThat(decodedAbort.getTxnId()).isEqualTo(43L);
    }

    @Test
    @DisplayName("Encodes and decodes UpdateLogRecord with before and after images")
    void testUpdateRecord() {
        byte[] before = new byte[]{1, 2, 3, 4};
        byte[] after = new byte[]{5, 6, 7, 8, 9};
        UpdateLogRecord update = new UpdateLogRecord(500L, 200L, 10L, "accounts", 3, 7, before, after);

        ByteBuffer buffer = ByteBuffer.allocate(256);
        LogRecordCodec.encode(update, buffer);
        buffer.flip();

        LogRecord decoded = LogRecordCodec.decode(buffer);
        assertThat(decoded).isInstanceOf(UpdateLogRecord.class);
        UpdateLogRecord u = (UpdateLogRecord) decoded;
        assertThat(u.getLsn()).isEqualTo(500L);
        assertThat(u.getPrevLsn()).isEqualTo(200L);
        assertThat(u.getTxnId()).isEqualTo(10L);
        assertThat(u.getTableName()).isEqualTo("accounts");
        assertThat(u.getPageId()).isEqualTo(3);
        assertThat(u.getSlotIndex()).isEqualTo(7);
        assertThat(u.getBeforeImage()).isEqualTo(before);
        assertThat(u.getAfterImage()).isEqualTo(after);
    }

    @Test
    @DisplayName("Encodes and decodes UpdateLogRecord with null before/after images")
    void testUpdateRecordWithNullImages() {
        // Insert: before is null
        UpdateLogRecord insert = new UpdateLogRecord(600L, 500L, 10L, "orders", 1, 0, null, new byte[]{42});
        ByteBuffer buffer = ByteBuffer.allocate(256);
        LogRecordCodec.encode(insert, buffer);
        buffer.flip();

        UpdateLogRecord decodedInsert = (UpdateLogRecord) LogRecordCodec.decode(buffer);
        assertThat(decodedInsert.getBeforeImage()).isNull();
        assertThat(decodedInsert.getAfterImage()).containsExactly((byte) 42);

        // Delete: after is null
        UpdateLogRecord delete = new UpdateLogRecord(700L, 600L, 10L, "orders", 1, 0, new byte[]{42}, null);
        buffer.clear();
        LogRecordCodec.encode(delete, buffer);
        buffer.flip();

        UpdateLogRecord decodedDelete = (UpdateLogRecord) LogRecordCodec.decode(buffer);
        assertThat(decodedDelete.getBeforeImage()).containsExactly((byte) 42);
        assertThat(decodedDelete.getAfterImage()).isNull();
    }

    @Test
    @DisplayName("Encodes and decodes CheckpointEndRecord with active transactions and dirty pages")
    void testCheckpointEndRecord() {
        Map<Long, Long> activeTxns = Map.of(101L, 1500L, 102L, 1800L);
        Map<Integer, Long> dirtyPages = Map.of(2, 1200L, 5, 1400L, 9, 1750L);
        CheckpointEndRecord record = new CheckpointEndRecord(2000L, activeTxns, dirtyPages);

        ByteBuffer buffer = ByteBuffer.allocate(512);
        LogRecordCodec.encode(record, buffer);
        buffer.flip();

        LogRecord decoded = LogRecordCodec.decode(buffer);
        assertThat(decoded).isInstanceOf(CheckpointEndRecord.class);
        CheckpointEndRecord c = (CheckpointEndRecord) decoded;
        assertThat(c.getActiveTransactionTable()).containsAllEntriesOf(activeTxns);
        assertThat(c.getDirtyPageTable()).containsAllEntriesOf(dirtyPages);
    }

    @Test
    @DisplayName("Fails decoding when CRC32 checksum is corrupted")
    void testCrcCorruptionDetection() {
        BeginLogRecord record = new BeginLogRecord(100L, 0L, 42L);
        ByteBuffer buffer = ByteBuffer.allocate(128);

        LogRecordCodec.encode(record, buffer);
        buffer.flip();

        // Corrupt a byte in the payload
        int corruptPos = buffer.position() + 10;
        buffer.put(corruptPos, (byte) (buffer.get(corruptPos) ^ 0xFF));

        assertThatThrownBy(() -> LogRecordCodec.decode(buffer))
                .isInstanceOf(CorruptedLogRecordException.class)
                .hasMessageContaining("CRC32 mismatch");
    }

    @Test
    @DisplayName("Fails decoding when magic number is invalid")
    void testInvalidMagicDetection() {
        BeginLogRecord record = new BeginLogRecord(100L, 0L, 42L);
        ByteBuffer buffer = ByteBuffer.allocate(128);

        LogRecordCodec.encode(record, buffer);
        buffer.flip();

        // Corrupt magic bytes (offset 4 after totalLength)
        buffer.putInt(4, 0x12345678);

        assertThatThrownBy(() -> LogRecordCodec.decode(buffer))
                .isInstanceOf(CorruptedLogRecordException.class)
                .hasMessageContaining("Invalid WAL record magic");
    }
}
