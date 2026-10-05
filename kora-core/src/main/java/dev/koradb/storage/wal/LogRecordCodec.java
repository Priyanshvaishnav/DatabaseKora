package dev.koradb.storage.wal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Binary serialization and deserialization codec for Write-Ahead Log records.
 *
 * <p>Enforces framing, length prefixes, magic sentinel verification, and 32-bit CRC32 checksums.
 */
public final class LogRecordCodec {

    public static final int MAGIC_WAL_RECORD = 0x57414C52; // "WALR"
    public static final int BASE_RECORD_OVERHEAD = 37;      // 4 (len) + 4 (magic) + 8 (lsn) + 8 (prevLsn) + 8 (txnId) + 1 (type) + 4 (crc32)

    private LogRecordCodec() {
    }

    /**
     * Calculates the total encoded byte length for the given log record.
     */
    public static int calculateEncodedLength(LogRecord record) {
        int length = BASE_RECORD_OVERHEAD;
        switch (record.getType()) {
            case BEGIN, COMMIT, ABORT -> {
                // No payload
            }
            case UPDATE -> {
                UpdateLogRecord u = (UpdateLogRecord) record;
                byte[] tableBytes = u.getTableName().getBytes(StandardCharsets.UTF_8);
                length += 2 + tableBytes.length; // table name
                length += 4 + 4;                // pageId + slotIndex
                length += 4 + (u.getBeforeImage() != null ? u.getBeforeImage().length : 0);
                length += 4 + (u.getAfterImage() != null ? u.getAfterImage().length : 0);
            }
            case CHECKPOINT_BEGIN -> {
                length += 8; // timestamp
            }
            case CHECKPOINT_END -> {
                CheckpointEndRecord c = (CheckpointEndRecord) record;
                length += 4 + (c.getActiveTransactionTable().size() * 16); // 8B txnId + 8B lastLsn
                length += 4 + (c.getDirtyPageTable().size() * 12);         // 4B pageId + 8B recLsn
            }
        }
        return length;
    }

    /**
     * Serializes a log record into the destination {@link ByteBuffer}.
     *
     * @param record log record to encode
     * @param buffer target byte buffer with sufficient remaining capacity
     */
    public static void encode(LogRecord record, ByteBuffer buffer) {
        Objects.requireNonNull(record, "record must not be null");
        Objects.requireNonNull(buffer, "buffer must not be null");

        int totalLength = calculateEncodedLength(record);
        if (buffer.remaining() < totalLength) {
            throw new IllegalArgumentException(
                    "Buffer has insufficient space: required %d, remaining %d"
                            .formatted(totalLength, buffer.remaining()));
        }

        buffer.putInt(totalLength);
        int crcStart = buffer.position();

        buffer.putInt(MAGIC_WAL_RECORD);
        buffer.putLong(record.getLsn());
        buffer.putLong(record.getPrevLsn());
        buffer.putLong(record.getTxnId());
        buffer.put(record.getType().getCode());

        switch (record.getType()) {
            case BEGIN, COMMIT, ABORT -> {
                // No payload
            }
            case UPDATE -> {
                UpdateLogRecord u = (UpdateLogRecord) record;
                byte[] tableBytes = u.getTableName().getBytes(StandardCharsets.UTF_8);
                buffer.putShort((short) tableBytes.length);
                buffer.put(tableBytes);
                buffer.putInt(u.getPageId());
                buffer.putInt(u.getSlotIndex());

                if (u.getBeforeImage() == null) {
                    buffer.putInt(-1);
                } else {
                    buffer.putInt(u.getBeforeImage().length);
                    buffer.put(u.getBeforeImage());
                }

                if (u.getAfterImage() == null) {
                    buffer.putInt(-1);
                } else {
                    buffer.putInt(u.getAfterImage().length);
                    buffer.put(u.getAfterImage());
                }
            }
            case CHECKPOINT_BEGIN -> {
                CheckpointBeginRecord c = (CheckpointBeginRecord) record;
                buffer.putLong(c.getTimestamp());
            }
            case CHECKPOINT_END -> {
                CheckpointEndRecord c = (CheckpointEndRecord) record;
                buffer.putInt(c.getActiveTransactionTable().size());
                for (Map.Entry<Long, Long> entry : c.getActiveTransactionTable().entrySet()) {
                    buffer.putLong(entry.getKey());
                    buffer.putLong(entry.getValue());
                }
                buffer.putInt(c.getDirtyPageTable().size());
                for (Map.Entry<Integer, Long> entry : c.getDirtyPageTable().entrySet()) {
                    buffer.putInt(entry.getKey());
                    buffer.putLong(entry.getValue());
                }
            }
        }

        int crcEnd = buffer.position();

        // Compute CRC32
        ByteBuffer slice = buffer.duplicate();
        slice.position(crcStart);
        slice.limit(crcEnd);

        CRC32 crc = new CRC32();
        crc.update(slice);
        buffer.putInt((int) crc.getValue());
    }

    /**
     * Deserializes a log record from the provided {@link ByteBuffer}.
     *
     * @param buffer source byte buffer positioned at the start of a log record
     * @return decoded {@link LogRecord}
     * @throws CorruptedLogRecordException if the magic number is invalid or CRC32 fails
     */
    public static LogRecord decode(ByteBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer must not be null");

        if (buffer.remaining() < BASE_RECORD_OVERHEAD) {
            throw new IllegalArgumentException("Buffer has fewer bytes than minimum record overhead");
        }

        int totalLength = buffer.getInt();
        if (totalLength < BASE_RECORD_OVERHEAD) {
            throw new CorruptedLogRecordException("Record length is less than header overhead: " + totalLength);
        }
        if (buffer.remaining() < totalLength - 4) {
            throw new IllegalArgumentException("Incomplete record in buffer: expected " + totalLength);
        }

        int crcStart = buffer.position();
        int crcEnd = crcStart + (totalLength - 4 - 4); // all bytes excluding totalLength and checksum

        int magic = buffer.getInt();
        if (magic != MAGIC_WAL_RECORD) {
            throw new CorruptedLogRecordException(
                    "Invalid WAL record magic: 0x%08X (expected 0x%08X)"
                            .formatted(magic, MAGIC_WAL_RECORD));
        }

        long lsn = buffer.getLong();
        long prevLsn = buffer.getLong();
        long txnId = buffer.getLong();
        byte typeCode = buffer.get();
        LogRecordType type = LogRecordType.fromCode(typeCode);

        LogRecord record = switch (type) {
            case BEGIN -> new BeginLogRecord(lsn, prevLsn, txnId);
            case COMMIT -> new CommitLogRecord(lsn, prevLsn, txnId);
            case ABORT -> new AbortLogRecord(lsn, prevLsn, txnId);
            case UPDATE -> {
                short tableLen = buffer.getShort();
                byte[] tableBytes = new byte[tableLen];
                buffer.get(tableBytes);
                String tableName = new String(tableBytes, StandardCharsets.UTF_8);

                int pageId = buffer.getInt();
                int slotIndex = buffer.getInt();

                int beforeLen = buffer.getInt();
                byte[] beforeImage = null;
                if (beforeLen >= 0) {
                    beforeImage = new byte[beforeLen];
                    buffer.get(beforeImage);
                }

                int afterLen = buffer.getInt();
                byte[] afterImage = null;
                if (afterLen >= 0) {
                    afterImage = new byte[afterLen];
                    buffer.get(afterImage);
                }

                yield new UpdateLogRecord(lsn, prevLsn, txnId, tableName, pageId, slotIndex, beforeImage, afterImage);
            }
            case CHECKPOINT_BEGIN -> {
                long timestamp = buffer.getLong();
                yield new CheckpointBeginRecord(lsn, timestamp);
            }
            case CHECKPOINT_END -> {
                int activeTxnCount = buffer.getInt();
                Map<Long, Long> activeTxns = new HashMap<>();
                for (int i = 0; i < activeTxnCount; i++) {
                    activeTxns.put(buffer.getLong(), buffer.getLong());
                }

                int dirtyPageCount = buffer.getInt();
                Map<Integer, Long> dirtyPages = new HashMap<>();
                for (int i = 0; i < dirtyPageCount; i++) {
                    dirtyPages.put(buffer.getInt(), buffer.getLong());
                }

                yield new CheckpointEndRecord(lsn, activeTxns, dirtyPages);
            }
        };

        // Validate CRC32
        ByteBuffer slice = buffer.duplicate();
        slice.position(crcStart);
        slice.limit(crcEnd);

        CRC32 crc = new CRC32();
        crc.update(slice);
        int expectedCrc = (int) crc.getValue();
        int actualCrc = buffer.getInt();

        if (expectedCrc != actualCrc) {
            throw new CorruptedLogRecordException(
                    "CRC32 mismatch on %s record at LSN %d (expected 0x%08X, got 0x%08X)"
                            .formatted(type, lsn, expectedCrc, actualCrc));
        }

        return record;
    }
}
