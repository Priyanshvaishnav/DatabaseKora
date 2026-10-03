package dev.koradb.storage;

import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.sql.ast.ColumnDef;
import dev.koradb.sql.ast.DataType;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the storage engine — Page, SlottedPage, and RecordSerializer.
 *
 * These tests do NOT touch the disk (no files created). They verify the
 * in-memory byte-level correctness of each storage primitive.
 */
@DisplayName("Storage Engine")
class StorageEngineTest {

    // =========================================================================
    // Page — raw byte read/write
    // =========================================================================

    @Nested
    @DisplayName("Page")
    class PageTest {

        @Test
        @DisplayName("new page is all zeros")
        void newPageIsZero() {
            Page page = new Page(0);
            for (byte b : page.getData()) {
                assertThat(b).isEqualTo((byte) 0);
            }
        }

        @Test
        @DisplayName("writeInt / readInt round-trip (big-endian)")
        void intRoundTrip() {
            Page page = new Page(0);
            page.writeInt(0, Integer.MAX_VALUE);
            assertThat(page.readInt(0)).isEqualTo(Integer.MAX_VALUE);

            page.writeInt(4, -1);
            assertThat(page.readInt(4)).isEqualTo(-1);

            page.writeInt(8, 0);
            assertThat(page.readInt(8)).isEqualTo(0);
        }

        @Test
        @DisplayName("writeLong / readLong round-trip")
        void longRoundTrip() {
            Page page = new Page(0);
            page.writeLong(0, Long.MAX_VALUE);
            assertThat(page.readLong(0)).isEqualTo(Long.MAX_VALUE);

            page.writeLong(8, Long.MIN_VALUE);
            assertThat(page.readLong(8)).isEqualTo(Long.MIN_VALUE);
        }

        @Test
        @DisplayName("writeShort / readShort round-trip")
        void shortRoundTrip() {
            Page page = new Page(0);
            page.writeShort(0, (short) 1234);
            assertThat(page.readShort(0)).isEqualTo((short) 1234);

            page.writeShort(2, (short) -1);
            assertThat(page.readShort(2)).isEqualTo((short) -1);
        }

        @Test
        @DisplayName("writeBytes / readBytes round-trip")
        void bytesRoundTrip() {
            Page   page  = new Page(0);
            byte[] bytes = {1, 2, 3, 4, 5};
            page.writeBytes(10, bytes);
            assertThat(page.readBytes(10, 5)).containsExactly(1, 2, 3, 4, 5);
        }

        @Test
        @DisplayName("out-of-bounds access throws IndexOutOfBoundsException")
        void outOfBounds() {
            Page page = new Page(0);
            assertThatThrownBy(() -> page.readInt(StorageConfig.PAGE_SIZE - 2))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("wrapping existing byte array preserves data")
        void wrapExistingArray() {
            byte[] data = new byte[StorageConfig.PAGE_SIZE];
            data[0] = 42;
            Page page = new Page(0, data);
            assertThat(page.readByte(0)).isEqualTo((byte) 42);
        }

        @Test
        @DisplayName("wrong size array is rejected")
        void wrongSizeArray() {
            assertThatThrownBy(() -> new Page(0, new byte[100]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactly");
        }
    }

    // =========================================================================
    // SlottedPage — slot directory and record management
    // =========================================================================

    @Nested
    @DisplayName("SlottedPage")
    class SlottedPageTest {

        private SlottedPage sp;

        @BeforeEach
        void setUp() {
            Page page = new Page(0);
            sp = new SlottedPage(page);
            sp.initialise();
        }

        @Test
        @DisplayName("fresh page has 0 slots and 0 live records")
        void freshPage() {
            assertThat(sp.slotCount()).isEqualTo(0);
            assertThat(sp.liveRecordCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("fresh page has correct free space")
        void freshFreeSpace() {
            int expected = StorageConfig.PAGE_SIZE - StorageConfig.PAGE_HEADER_SIZE;
            assertThat(sp.freeSpace()).isEqualTo(expected);
        }

        @Test
        @DisplayName("insert one record — slot 0 returned")
        void insertOneRecord() {
            byte[] record = {1, 2, 3, 4};
            int slot = sp.insertRecord(record);
            assertThat(slot).isEqualTo(0);
            assertThat(sp.slotCount()).isEqualTo(1);
            assertThat(sp.liveRecordCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("insert multiple records — slots are sequential")
        void insertMultipleRecords() {
            for (int i = 0; i < 5; i++) {
                int slot = sp.insertRecord(new byte[]{(byte) i});
                assertThat(slot).isEqualTo(i);
            }
            assertThat(sp.slotCount()).isEqualTo(5);
            assertThat(sp.liveRecordCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("getRecord returns the exact bytes inserted")
        void getRecordRoundTrip() {
            byte[] record = "hello world".getBytes();
            int slot = sp.insertRecord(record);
            assertThat(sp.getRecord(slot)).containsExactly(record);
        }

        @Test
        @DisplayName("delete marks slot as tombstone, reduces live count")
        void deleteRecord() {
            int slot = sp.insertRecord(new byte[]{10, 20});
            assertThat(sp.liveRecordCount()).isEqualTo(1);

            sp.deleteRecord(slot);

            assertThat(sp.isAlive(slot)).isFalse();
            assertThat(sp.liveRecordCount()).isEqualTo(0);
            assertThat(sp.slotCount()).isEqualTo(1); // slot still exists
        }

        @Test
        @DisplayName("reading a deleted slot throws StorageException")
        void getDeletedSlot() {
            int slot = sp.insertRecord(new byte[]{1});
            sp.deleteRecord(slot);
            assertThatThrownBy(() -> sp.getRecord(slot))
                    .isInstanceOf(dev.koradb.exception.StorageException.class)
                    .hasMessageContaining("deleted");
        }

        @Test
        @DisplayName("inserting after delete preserves the tombstone slot")
        void insertAfterDelete() {
            int slot0 = sp.insertRecord(new byte[]{1});
            sp.deleteRecord(slot0);

            // New insert gets slot 1 (slot 0 is a tombstone — never reused)
            int slot1 = sp.insertRecord(new byte[]{2});
            assertThat(slot1).isEqualTo(1);
            assertThat(sp.slotCount()).isEqualTo(2);
            assertThat(sp.liveRecordCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("free space decreases by SLOT_SIZE + recordLength per insert")
        void freeSpaceDecrements() {
            int initial = sp.freeSpace();
            byte[] rec  = new byte[100];
            sp.insertRecord(rec);
            assertThat(sp.freeSpace())
                    .isEqualTo(initial - StorageConfig.SLOT_SIZE - rec.length);
        }

        @Test
        @DisplayName("out-of-range slot access throws StorageException")
        void outOfRangeSlot() {
            assertThatThrownBy(() -> sp.getRecord(0))
                    .isInstanceOf(dev.koradb.exception.StorageException.class);
        }

        @Test
        @DisplayName("page data survives re-wrap (simulates disk round-trip)")
        void pageRewrap() {
            byte[] record = {42, 43, 44};
            sp.insertRecord(record);

            // Simulate: write page to disk and read it back by using the same byte array
            byte[]      savedData = sp.getPage().getData().clone();
            Page        restored  = new Page(0, savedData);
            SlottedPage sp2       = new SlottedPage(restored);

            assertThat(sp2.slotCount()).isEqualTo(1);
            assertThat(sp2.getRecord(0)).containsExactly(record);
        }
    }

    // =========================================================================
    // RecordSerializer — Row ↔ byte[] round-trip
    // =========================================================================

    @Nested
    @DisplayName("RecordSerializer")
    class RecordSerializerTest {

        private Schema buildSchema(ColumnDef... cols) {
            return new Schema(List.of(cols));
        }

        @Test
        @DisplayName("INT round-trip")
        void intRoundTrip() {
            Schema schema = buildSchema(new ColumnDef("id", DataType.INT));
            RecordSerializer ser = new RecordSerializer(schema);

            Row original = new Row(Map.of("id", 42));
            Row restored = ser.deserialize(ser.serialize(original));

            assertThat(restored.get("id")).isEqualTo(42);
        }

        @Test
        @DisplayName("BIGINT round-trip")
        void bigintRoundTrip() {
            Schema schema = buildSchema(new ColumnDef("n", DataType.BIGINT));
            RecordSerializer ser = new RecordSerializer(schema);

            Row original = new Row(Map.of("n", Long.MAX_VALUE));
            Row restored = ser.deserialize(ser.serialize(original));

            assertThat(restored.get("n")).isEqualTo(Long.MAX_VALUE);
        }

        @Test
        @DisplayName("DOUBLE round-trip")
        void doubleRoundTrip() {
            Schema schema = buildSchema(new ColumnDef("price", DataType.DOUBLE));
            RecordSerializer ser = new RecordSerializer(schema);

            Row original = new Row(Map.of("price", 3.14159));
            Row restored = ser.deserialize(ser.serialize(original));

            assertThat((Double) restored.get("price")).isCloseTo(3.14159, within(0.0001));
        }

        @Test
        @DisplayName("BOOLEAN round-trip — true and false")
        void booleanRoundTrip() {
            Schema schema = buildSchema(new ColumnDef("active", DataType.BOOLEAN));
            RecordSerializer ser = new RecordSerializer(schema);

            Row trueRow  = new Row(Map.of("active", true));
            Row falseRow = new Row(Map.of("active", false));

            assertThat(ser.deserialize(ser.serialize(trueRow)).get("active")).isEqualTo(true);
            assertThat(ser.deserialize(ser.serialize(falseRow)).get("active")).isEqualTo(false);
        }

        @Test
        @DisplayName("VARCHAR round-trip — simple string")
        void varcharRoundTrip() {
            Schema schema = buildSchema(new ColumnDef("name", DataType.VARCHAR));
            RecordSerializer ser = new RecordSerializer(schema);

            Row original = new Row(Map.of("name", "Alice Smith"));
            Row restored = ser.deserialize(ser.serialize(original));

            assertThat(restored.get("name")).isEqualTo("Alice Smith");
        }

        @Test
        @DisplayName("VARCHAR round-trip — empty string")
        void varcharEmpty() {
            Schema schema = buildSchema(new ColumnDef("tag", DataType.VARCHAR));
            RecordSerializer ser = new RecordSerializer(schema);

            Row original = new Row(Map.of("tag", ""));
            Row restored = ser.deserialize(ser.serialize(original));

            assertThat(restored.get("tag")).isEqualTo("");
        }

        @Test
        @DisplayName("TIMESTAMP round-trip")
        void timestampRoundTrip() {
            Schema schema = buildSchema(new ColumnDef("created", DataType.TIMESTAMP));
            RecordSerializer ser = new RecordSerializer(schema);

            java.time.LocalDateTime ts = java.time.LocalDateTime.of(2024, 6, 15, 12, 30, 0);
            Row original = new Row(Map.of("created", ts));
            Row restored = ser.deserialize(ser.serialize(original));

            assertThat(restored.get("created")).isEqualTo(ts);
        }

        @Test
        @DisplayName("multi-column schema round-trip")
        void multiColumnRoundTrip() {
            Schema schema = buildSchema(
                    new ColumnDef("id",     DataType.INT),
                    new ColumnDef("name",   DataType.VARCHAR),
                    new ColumnDef("salary", DataType.DOUBLE),
                    new ColumnDef("active", DataType.BOOLEAN)
            );
            RecordSerializer ser = new RecordSerializer(schema);

            Row original = new Row(new java.util.LinkedHashMap<>(Map.of(
                    "id",     1,
                    "name",   "Carol",
                    "salary", 120000.50,
                    "active", true
            )));

            Row restored = ser.deserialize(ser.serialize(original));

            assertThat(restored.get("id")).isEqualTo(1);
            assertThat(restored.get("name")).isEqualTo("Carol");
            assertThat((Double) restored.get("salary")).isCloseTo(120000.50, within(0.01));
            assertThat(restored.get("active")).isEqualTo(true);
        }

        @Test
        @DisplayName("null bitmap size — ceil(N/8)")
        void nullBitmapSize() {
            assertThat(RecordSerializer.nullBitmapSize(1)).isEqualTo(1);
            assertThat(RecordSerializer.nullBitmapSize(8)).isEqualTo(1);
            assertThat(RecordSerializer.nullBitmapSize(9)).isEqualTo(2);
            assertThat(RecordSerializer.nullBitmapSize(16)).isEqualTo(2);
            assertThat(RecordSerializer.nullBitmapSize(17)).isEqualTo(3);
        }
    }
}
