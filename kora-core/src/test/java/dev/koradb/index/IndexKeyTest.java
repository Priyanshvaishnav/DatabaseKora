package dev.koradb.index;

import dev.koradb.sql.ast.DataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IndexKey")
class IndexKeyTest {

    @Nested
    @DisplayName("Comparisons")
    class Comparisons {

        @Test
        @DisplayName("Compares integer keys correctly")
        void integerComparisons() {
            IndexKey k1 = IndexKey.ofInt(10);
            IndexKey k2 = IndexKey.ofInt(20);
            IndexKey k3 = IndexKey.ofInt(10);

            assertThat(k1.compareTo(k2)).isLessThan(0);
            assertThat(k2.compareTo(k1)).isGreaterThan(0);
            assertThat(k1.compareTo(k3)).isZero();
            assertThat(k1).isEqualTo(k3);
        }

        @Test
        @DisplayName("Compares string keys lexicographically")
        void stringComparisons() {
            IndexKey k1 = IndexKey.ofString("alice");
            IndexKey k2 = IndexKey.ofString("bob");

            assertThat(k1.compareTo(k2)).isLessThan(0);
            assertThat(k2.compareTo(k1)).isGreaterThan(0);
        }

        @Test
        @DisplayName("Compares double keys correctly")
        void doubleComparisons() {
            IndexKey k1 = IndexKey.ofDouble(3.14);
            IndexKey k2 = IndexKey.ofDouble(2.71);

            assertThat(k1.compareTo(k2)).isGreaterThan(0);
            assertThat(k2.compareTo(k1)).isLessThan(0);
        }

        @Test
        @DisplayName("Compares timestamp keys chronologically")
        void timestampComparisons() {
            LocalDateTime t1 = LocalDateTime.of(2026, 1, 1, 12, 0);
            LocalDateTime t2 = LocalDateTime.of(2026, 1, 2, 12, 0);

            IndexKey k1 = IndexKey.ofTimestamp(t1);
            IndexKey k2 = IndexKey.ofTimestamp(t2);

            assertThat(k1.compareTo(k2)).isLessThan(0);
        }

        @Test
        @DisplayName("Null keys sort before non-null keys")
        void nullComparisons() {
            IndexKey kNull = new IndexKey(null, DataType.INT);
            IndexKey kVal = IndexKey.ofInt(5);

            assertThat(kNull.compareTo(kVal)).isLessThan(0);
            assertThat(kVal.compareTo(kNull)).isGreaterThan(0);
            assertThat(kNull.compareTo(new IndexKey(null, DataType.INT))).isZero();
        }
    }

    @Nested
    @DisplayName("Binary Serialization")
    class Serialization {

        @Test
        @DisplayName("Serializes and deserializes INT key roundtrip")
        void intRoundtrip() {
            IndexKey key = IndexKey.ofInt(42);
            byte[] bytes = key.toBytes();
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            IndexKey deserialized = IndexKey.fromByteBuffer(buf, DataType.INT);

            assertThat(deserialized).isEqualTo(key);
            assertThat(deserialized.getValue()).isEqualTo(42);
        }

        @Test
        @DisplayName("Serializes and deserializes BIGINT key roundtrip")
        void longRoundtrip() {
            IndexKey key = IndexKey.ofLong(9876543210L);
            byte[] bytes = key.toBytes();
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            IndexKey deserialized = IndexKey.fromByteBuffer(buf, DataType.BIGINT);

            assertThat(deserialized).isEqualTo(key);
            assertThat(deserialized.getValue()).isEqualTo(9876543210L);
        }

        @Test
        @DisplayName("Serializes and deserializes VARCHAR key roundtrip")
        void stringRoundtrip() {
            IndexKey key = IndexKey.ofString("KoraDB B+ Tree");
            byte[] bytes = key.toBytes();
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            IndexKey deserialized = IndexKey.fromByteBuffer(buf, DataType.VARCHAR);

            assertThat(deserialized).isEqualTo(key);
            assertThat(deserialized.getValue()).isEqualTo("KoraDB B+ Tree");
        }

        @Test
        @DisplayName("Serializes and deserializes DOUBLE key roundtrip")
        void doubleRoundtrip() {
            IndexKey key = IndexKey.ofDouble(123.456);
            byte[] bytes = key.toBytes();
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            IndexKey deserialized = IndexKey.fromByteBuffer(buf, DataType.DOUBLE);

            assertThat(deserialized).isEqualTo(key);
            assertThat(deserialized.getValue()).isEqualTo(123.456);
        }

        @Test
        @DisplayName("Serializes and deserializes TIMESTAMP key roundtrip")
        void timestampRoundtrip() {
            LocalDateTime now = LocalDateTime.of(2026, 10, 4, 18, 30, 0);
            IndexKey key = IndexKey.ofTimestamp(now);
            byte[] bytes = key.toBytes();
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            IndexKey deserialized = IndexKey.fromByteBuffer(buf, DataType.TIMESTAMP);

            assertThat(deserialized).isEqualTo(key);
            assertThat(deserialized.getValue()).isEqualTo(now);
        }

        @Test
        @DisplayName("Serializes null key roundtrip")
        void nullRoundtrip() {
            IndexKey key = new IndexKey(null, DataType.INT);
            byte[] bytes = key.toBytes();
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            IndexKey deserialized = IndexKey.fromByteBuffer(buf, DataType.INT);

            assertThat(deserialized.getValue()).isNull();
        }
    }
}
