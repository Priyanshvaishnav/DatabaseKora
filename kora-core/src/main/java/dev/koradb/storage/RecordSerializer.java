package dev.koradb.storage;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.exception.StorageException;
import dev.koradb.sql.ast.ColumnDef;
import dev.koradb.sql.ast.DataType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a {@link Row} to a byte array and back using a fixed binary format.
 *
 * <h2>Record wire format</h2>
 * <pre>
 * [NULL BITMAP: ceil(N/8) bytes]
 * [COLUMN 0 bytes]
 * [COLUMN 1 bytes]
 * ...
 * [COLUMN N-1 bytes]
 * </pre>
 *
 * <h2>Per-type encoding (big-endian)</h2>
 * <pre>
 * INT       → 4 bytes (signed big-endian int)
 * BIGINT    → 8 bytes (signed big-endian long)
 * DOUBLE    → 8 bytes (Double.doubleToLongBits → big-endian long)
 * BOOLEAN   → 1 byte (0x00 = false, 0x01 = true)
 * VARCHAR   → 2-byte unsigned length + UTF-8 bytes (max 65535 chars)
 * TIMESTAMP → 8 bytes (epoch seconds UTC, signed big-endian long)
 * </pre>
 *
 * <h2>NULL handling</h2>
 * <p>If column {@code i} is NULL, bit {@code i % 8} of byte {@code i / 8}
 * in the null bitmap is set to 1. The column's bytes are omitted entirely
 * (length = 0). On deserialization, NULL columns are restored as Java {@code null}.
 *
 * <h2>Thread safety</h2>
 * <p>Stateless — safe for concurrent use.
 */
public final class RecordSerializer {

    private final Schema schema;

    public RecordSerializer(Schema schema) {
        this.schema = schema;
    }

    public static final int HEADER_SIZE = 16; // 8 bytes xmin + 8 bytes xmax

    public static long extractXmin(byte[] record) {
        if (record == null || record.length < HEADER_SIZE) return 0L;
        return java.nio.ByteBuffer.wrap(record).getLong(0);
    }

    public static long extractXmax(byte[] record) {
        if (record == null || record.length < HEADER_SIZE) return 0L;
        return java.nio.ByteBuffer.wrap(record).getLong(8);
    }

    public static void setXmax(byte[] record, long xmax) {
        if (record != null && record.length >= HEADER_SIZE) {
            java.nio.ByteBuffer.wrap(record).putLong(8, xmax);
        }
    }

    // =========================================================================
    // Serialization: Row → byte[]
    // =========================================================================

    /**
     * Serializes a {@link Row} into a byte array according to the schema.
     * Includes the 16-byte MVCC version header ([xmin: 8B][xmax: 8B]).
     *
     * @param row the row to serialize; must have a value (or null) for every column
     * @return the serialized bytes, ready to write into a {@link SlottedPage}
     */
    public byte[] serialize(Row row) {
        List<ColumnDef> cols        = schema.columns();
        int             bitmapBytes = nullBitmapSize(cols.size());

        // First pass: compute total byte size
        byte[] nullBitmap = new byte[bitmapBytes];
        byte[][] encoded  = new byte[cols.size()][];

        for (int i = 0; i < cols.size(); i++) {
            ColumnDef col   = cols.get(i);
            Object    value = row.get(col.name());

            if (value == null) {
                setBit(nullBitmap, i);   // mark as NULL
                encoded[i] = new byte[0];
            } else {
                encoded[i] = encodeValue(value, col.dataType(), col.name());
            }
        }

        // Compute total length
        int total = HEADER_SIZE + bitmapBytes;
        for (byte[] b : encoded) total += b.length;

        // Second pass: write into a single array
        byte[] record = new byte[total];
        java.nio.ByteBuffer.wrap(record).putLong(0, row.getXmin());
        java.nio.ByteBuffer.wrap(record).putLong(8, row.getXmax());

        int pos = HEADER_SIZE;
        System.arraycopy(nullBitmap, 0, record, pos, bitmapBytes);
        pos += bitmapBytes;

        for (byte[] b : encoded) {
            System.arraycopy(b, 0, record, pos, b.length);
            pos += b.length;
        }

        return record;
    }

    // =========================================================================
    // Deserialization: byte[] → Row
    // =========================================================================

    /**
     * Deserializes a byte array back into a {@link Row} according to the schema.
     * Reconstructs the {@link Row} along with its {@code xmin} and {@code xmax} coordinates.
     *
     * @param record the bytes previously produced by {@link #serialize}
     * @return a reconstructed {@link Row}
     */
    public Row deserialize(byte[] record) {
        List<ColumnDef> cols = schema.columns();
        int bitmapBytes = nullBitmapSize(cols.size());

        long xmin = 0L;
        long xmax = 0L;
        int headerOffset = 0;

        if (record.length >= HEADER_SIZE + bitmapBytes) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(record);
            xmin = buf.getLong(0);
            xmax = buf.getLong(8);
            headerOffset = HEADER_SIZE;
        }

        byte[] nullBitmap = new byte[bitmapBytes];
        System.arraycopy(record, headerOffset, nullBitmap, 0, bitmapBytes);

        Map<String, Object> values = new LinkedHashMap<>();
        int pos = headerOffset + bitmapBytes;

        for (int i = 0; i < cols.size(); i++) {
            ColumnDef col = cols.get(i);
            if (getBit(nullBitmap, i)) {
                values.put(col.name(), null);
            } else {
                Object[] result = decodeValue(record, pos, col.dataType(), col.name());
                values.put(col.name(), result[0]);
                pos += (int) result[1]; // advance by the number of bytes consumed
            }
        }

        return new Row(values, xmin, xmax);
    }

    // =========================================================================
    // Per-type encoding
    // =========================================================================

    private byte[] encodeValue(Object value, DataType type, String colName) {
        return switch (type) {
            case INT       -> encodeInt((Integer) value);
            case BIGINT    -> encodeLong((Long) value);
            case DOUBLE    -> encodeLong(Double.doubleToLongBits((Double) value));
            case BOOLEAN   -> new byte[]{ (byte) ((Boolean) value ? 1 : 0) };
            case VARCHAR   -> encodeVarchar((String) value);
            case TIMESTAMP -> encodeLong(
                    ((LocalDateTime) value).toEpochSecond(ZoneOffset.UTC));
        };
    }

    /** Returns {decodedValue, bytesConsumed} */
    private Object[] decodeValue(byte[] record, int pos, DataType type, String colName) {
        return switch (type) {
            case INT -> new Object[]{ readInt(record, pos), 4 };
            case BIGINT -> new Object[]{ readLong(record, pos), 8 };
            case DOUBLE -> new Object[]{
                    Double.longBitsToDouble(readLong(record, pos)), 8 };
            case BOOLEAN -> new Object[]{ record[pos] != 0, 1 };
            case VARCHAR -> {
                int len = Short.toUnsignedInt(readShort(record, pos));
                String s = new String(record, pos + 2, len, StandardCharsets.UTF_8);
                yield new Object[]{ s, 2 + len };
            }
            case TIMESTAMP -> {
                long epochSec = readLong(record, pos);
                LocalDateTime ldt = LocalDateTime.ofEpochSecond(
                        epochSec, 0, ZoneOffset.UTC);
                yield new Object[]{ ldt, 8 };
            }
        };
    }

    // =========================================================================
    // Primitive encoders
    // =========================================================================

    private byte[] encodeInt(int value) {
        return new byte[]{
            (byte)((value >> 24) & 0xFF),
            (byte)((value >> 16) & 0xFF),
            (byte)((value >>  8) & 0xFF),
            (byte) (value        & 0xFF)
        };
    }

    private byte[] encodeLong(long value) {
        return new byte[]{
            (byte)((value >> 56) & 0xFF),
            (byte)((value >> 48) & 0xFF),
            (byte)((value >> 40) & 0xFF),
            (byte)((value >> 32) & 0xFF),
            (byte)((value >> 24) & 0xFF),
            (byte)((value >> 16) & 0xFF),
            (byte)((value >>  8) & 0xFF),
            (byte) (value        & 0xFF)
        };
    }

    private byte[] encodeVarchar(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > 65535) {
            throw new StorageException(
                    "VARCHAR value exceeds 65535 UTF-8 bytes (%d bytes)".formatted(utf8.length));
        }
        byte[] result = new byte[2 + utf8.length];
        result[0] = (byte)((utf8.length >> 8) & 0xFF);
        result[1] = (byte) (utf8.length       & 0xFF);
        System.arraycopy(utf8, 0, result, 2, utf8.length);
        return result;
    }

    // =========================================================================
    // Primitive decoders (big-endian reads from arbitrary offsets)
    // =========================================================================

    private int readInt(byte[] buf, int pos) {
        return ((buf[pos]     & 0xFF) << 24)
             | ((buf[pos + 1] & 0xFF) << 16)
             | ((buf[pos + 2] & 0xFF) <<  8)
             |  (buf[pos + 3] & 0xFF);
    }

    private long readLong(byte[] buf, int pos) {
        return ((long)(buf[pos]     & 0xFF) << 56)
             | ((long)(buf[pos + 1] & 0xFF) << 48)
             | ((long)(buf[pos + 2] & 0xFF) << 40)
             | ((long)(buf[pos + 3] & 0xFF) << 32)
             | ((long)(buf[pos + 4] & 0xFF) << 24)
             | ((long)(buf[pos + 5] & 0xFF) << 16)
             | ((long)(buf[pos + 6] & 0xFF) <<  8)
             | ((long)(buf[pos + 7] & 0xFF));
    }

    private short readShort(byte[] buf, int pos) {
        return (short)(((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF));
    }

    // =========================================================================
    // Null bitmap helpers
    // =========================================================================

    static int nullBitmapSize(int columnCount) {
        return (columnCount + 7) / 8;
    }

    private void setBit(byte[] bitmap, int bitIndex) {
        bitmap[bitIndex / 8] |= (byte)(1 << (bitIndex % 8));
    }

    private boolean getBit(byte[] bitmap, int bitIndex) {
        return (bitmap[bitIndex / 8] & (1 << (bitIndex % 8))) != 0;
    }
}
