package dev.koradb.index;

import dev.koradb.exception.StorageException;
import dev.koradb.sql.ast.DataType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A typed, comparable search key used in B+ Tree indexes.
 *
 * <p>Supports all KoraDB {@link DataType} types:
 * <ul>
 *   <li>{@link DataType#INT} — 4 bytes</li>
 *   <li>{@link DataType#BIGINT} — 8 bytes</li>
 *   <li>{@link DataType#DOUBLE} — 8 bytes (IEEE 754)</li>
 *   <li>{@link DataType#BOOLEAN} — 1 byte</li>
 *   <li>{@link DataType#VARCHAR} — 2-byte length prefix + UTF-8 bytes</li>
 *   <li>{@link DataType#TIMESTAMP} — 8-byte epoch second</li>
 * </ul>
 *
 * <p>All serialized values use big-endian ordering to maintain platform independence.
 */
public final class IndexKey implements Comparable<IndexKey> {

    private final Object value;
    private final DataType type;

    public IndexKey(Object value, DataType type) {
        if (type == null) {
            throw new IllegalArgumentException("Key type cannot be null");
        }
        this.type = type;
        this.value = normalize(value, type);
    }

    public static IndexKey of(Object value, DataType type) {
        return new IndexKey(value, type);
    }

    public static IndexKey ofInt(int value) {
        return new IndexKey(value, DataType.INT);
    }

    public static IndexKey ofLong(long value) {
        return new IndexKey(value, DataType.BIGINT);
    }

    public static IndexKey ofDouble(double value) {
        return new IndexKey(value, DataType.DOUBLE);
    }

    public static IndexKey ofString(String value) {
        return new IndexKey(value, DataType.VARCHAR);
    }

    public static IndexKey ofBoolean(boolean value) {
        return new IndexKey(value, DataType.BOOLEAN);
    }

    public static IndexKey ofTimestamp(LocalDateTime value) {
        return new IndexKey(value, DataType.TIMESTAMP);
    }

    public Object getValue() {
        return value;
    }

    public DataType getType() {
        return type;
    }

    /**
     * Serializes this key into a byte array.
     */
    public byte[] toBytes() {
        if (value == null) {
            return new byte[]{0}; // null marker
        }

        return switch (type) {
            case INT -> ByteBuffer.allocate(5)
                    .put((byte) 1)
                    .putInt((Integer) value)
                    .array();
            case BIGINT -> ByteBuffer.allocate(9)
                    .put((byte) 1)
                    .putLong((Long) value)
                    .array();
            case DOUBLE -> ByteBuffer.allocate(9)
                    .put((byte) 1)
                    .putDouble((Double) value)
                    .array();
            case BOOLEAN -> new byte[]{1, (byte) ((Boolean) value ? 1 : 0)};
            case VARCHAR -> {
                byte[] utf8 = ((String) value).getBytes(StandardCharsets.UTF_8);
                if (utf8.length > 65535) {
                    throw new StorageException("IndexKey VARCHAR exceeds 65535 bytes");
                }
                ByteBuffer buf = ByteBuffer.allocate(3 + utf8.length);
                buf.put((byte) 1);
                buf.putShort((short) utf8.length);
                buf.put(utf8);
                yield buf.array();
            }
            case TIMESTAMP -> {
                long epochSec = ((LocalDateTime) value).toEpochSecond(ZoneOffset.UTC);
                yield ByteBuffer.allocate(9)
                        .put((byte) 1)
                        .putLong(epochSec)
                        .array();
            }
        };
    }

    /**
     * Deserializes an IndexKey from a ByteBuffer.
     */
    public static IndexKey fromByteBuffer(ByteBuffer buf, DataType type) {
        byte isPresent = buf.get();
        if (isPresent == 0) {
            return new IndexKey(null, type);
        }

        Object val = switch (type) {
            case INT -> buf.getInt();
            case BIGINT -> buf.getLong();
            case DOUBLE -> buf.getDouble();
            case BOOLEAN -> buf.get() != 0;
            case VARCHAR -> {
                int len = Short.toUnsignedInt(buf.getShort());
                byte[] bytes = new byte[len];
                buf.get(bytes);
                yield new String(bytes, StandardCharsets.UTF_8);
            }
            case TIMESTAMP -> {
                long epochSec = buf.getLong();
                yield LocalDateTime.ofEpochSecond(epochSec, 0, ZoneOffset.UTC);
            }
        };
        return new IndexKey(val, type);
    }

    private static Object normalize(Object val, DataType targetType) {
        if (val == null) return null;
        return switch (targetType) {
            case INT -> {
                if (val instanceof Number n) yield n.intValue();
                yield Integer.parseInt(val.toString().trim());
            }
            case BIGINT -> {
                if (val instanceof Number n) yield n.longValue();
                yield Long.parseLong(val.toString().trim());
            }
            case DOUBLE -> {
                if (val instanceof Number n) yield n.doubleValue();
                yield Double.parseDouble(val.toString().trim());
            }
            case BOOLEAN -> {
                if (val instanceof Boolean b) yield b;
                yield Boolean.parseBoolean(val.toString().trim());
            }
            case VARCHAR -> val.toString();
            case TIMESTAMP -> {
                if (val instanceof LocalDateTime ldt) yield ldt;
                yield LocalDateTime.parse(val.toString().trim().replace(" ", "T"));
            }
        };
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public int compareTo(IndexKey o) {
        if (o == null) return 1;
        if (this.value == null && o.value == null) return 0;
        if (this.value == null) return -1;
        if (o.value == null) return 1;

        if (this.value instanceof Number n1 && o.value instanceof Number n2) {
            return Double.compare(n1.doubleValue(), n2.doubleValue());
        }

        if (this.value instanceof Comparable c1 && o.value instanceof Comparable c2) {
            if (c1.getClass().isAssignableFrom(c2.getClass()) || c2.getClass().isAssignableFrom(c1.getClass())) {
                return c1.compareTo(c2);
            }
        }

        return this.value.toString().compareTo(o.value.toString());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IndexKey indexKey)) return false;
        if (type != indexKey.type) return false;
        return Objects.equals(value, indexKey.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, type);
    }

    @Override
    public String toString() {
        return "Key(" + value + ":" + type + ")";
    }
}
