package dev.koradb.index;

import dev.koradb.sql.ast.DataType;
import dev.koradb.storage.Page;
import dev.koradb.storage.RecordId;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A leaf node in a B+ Tree index.
 *
 * <p>Leaf nodes store sorted {@code (IndexKey, RecordId)} entries.
 * Leaf nodes are linked via {@code prevPageId} and {@code nextPageId}
 * forming a doubly-linked list for fast range scans.
 */
public final class BPlusTreeLeafNode {

    private final int pageId;
    private int parentId;
    private int prevPageId;
    private int nextPageId;
    private final DataType keyType;

    private final List<IndexKey> keys;
    private final List<RecordId> values;

    public BPlusTreeLeafNode(int pageId, int parentId, int prevPageId, int nextPageId, DataType keyType) {
        this.pageId = pageId;
        this.parentId = parentId;
        this.prevPageId = prevPageId;
        this.nextPageId = nextPageId;
        this.keyType = keyType;
        this.keys = new ArrayList<>();
        this.values = new ArrayList<>();
    }

    public int getPageId() { return pageId; }
    public int getParentId() { return parentId; }
    public void setParentId(int parentId) { this.parentId = parentId; }
    public int getPrevPageId() { return prevPageId; }
    public void setPrevPageId(int prevPageId) { this.prevPageId = prevPageId; }
    public int getNextPageId() { return nextPageId; }
    public void setNextPageId(int nextPageId) { this.nextPageId = nextPageId; }
    public DataType getKeyType() { return keyType; }
    public List<IndexKey> getKeys() { return keys; }
    public List<RecordId> getValues() { return values; }
    public int getKeyCount() { return keys.size(); }

    /**
     * Finds the index of the key using binary search.
     * Returns >= 0 if found, or -(insertion_point) - 1 if not found.
     */
    public int binarySearch(IndexKey key) {
        return Collections.binarySearch(keys, key);
    }

    /**
     * Looks up the first RecordId associated with key.
     */
    public Optional<RecordId> find(IndexKey key) {
        int idx = binarySearch(key);
        if (idx >= 0) {
            return Optional.of(values.get(idx));
        }
        return Optional.empty();
    }

    /**
     * Inserts (key, rid) in sorted order.
     */
    public void insert(IndexKey key, RecordId rid) {
        int idx = binarySearch(key);
        if (idx < 0) {
            idx = -(idx + 1);
        }
        keys.add(idx, key);
        values.add(idx, rid);
    }

    /**
     * Deletes the entry matching key and optional rid.
     * If rid is null, deletes the first matching key.
     * Returns true if an entry was removed.
     */
    public boolean delete(IndexKey key, RecordId rid) {
        int idx = binarySearch(key);
        if (idx < 0) return false;

        if (rid == null) {
            keys.remove(idx);
            values.remove(idx);
            return true;
        }

        // Handle duplicates or exact rid match
        int i = idx;
        while (i < keys.size() && keys.get(i).compareTo(key) == 0) {
            if (values.get(i).equals(rid)) {
                keys.remove(i);
                values.remove(i);
                return true;
            }
            i++;
        }
        i = idx - 1;
        while (i >= 0 && keys.get(i).compareTo(key) == 0) {
            if (values.get(i).equals(rid)) {
                keys.remove(i);
                values.remove(i);
                return true;
            }
            i--;
        }
        return false;
    }

    /**
     * Serializes this leaf node into the given Page buffer.
     */
    public void serialize(Page page) {
        ByteBuffer buf = ByteBuffer.wrap(page.getData());
        buf.put(BPlusTreeNodeType.LEAF.getCode()); // 1B
        buf.putShort((short) keys.size());        // 2B
        buf.putInt(parentId);                     // 4B
        buf.putInt(prevPageId);                   // 4B
        buf.putInt(nextPageId);                   // 4B

        for (int i = 0; i < keys.size(); i++) {
            buf.put(keys.get(i).toBytes());
            RecordId rid = values.get(i);
            buf.putInt(rid.pageId());
            buf.putInt(rid.slotIndex());
        }
    }

    /**
     * Deserializes a leaf node from a Page.
     */
    public static BPlusTreeLeafNode deserialize(Page page, DataType keyType) {
        ByteBuffer buf = ByteBuffer.wrap(page.getData());
        byte typeCode = buf.get();
        if (typeCode != BPlusTreeNodeType.LEAF.getCode()) {
            throw new IllegalArgumentException("Page " + page.getPageId() + " is not a leaf node (type=" + typeCode + ")");
        }

        int count = Short.toUnsignedInt(buf.getShort());
        int parentId = buf.getInt();
        int prevPageId = buf.getInt();
        int nextPageId = buf.getInt();

        BPlusTreeLeafNode node = new BPlusTreeLeafNode(page.getPageId(), parentId, prevPageId, nextPageId, keyType);
        for (int i = 0; i < count; i++) {
            IndexKey key = IndexKey.fromByteBuffer(buf, keyType);
            int ridPid = buf.getInt();
            int ridSlot = buf.getInt();
            node.keys.add(key);
            node.values.add(new RecordId(ridPid, ridSlot));
        }
        return node;
    }
}
