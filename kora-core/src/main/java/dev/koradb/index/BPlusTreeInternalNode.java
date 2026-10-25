package dev.koradb.index;

import dev.koradb.sql.ast.DataType;
import dev.koradb.storage.Page;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An internal (non-leaf) router node in a B+ Tree index.
 *
 * <p>Stores {@code K} keys and {@code K + 1} child page pointers.
 * Child pointers point to lower-level internal nodes or leaf nodes.
 */
public final class BPlusTreeInternalNode {

    private final int pageId;
    private int parentId;
    private final DataType keyType;

    private final List<IndexKey> keys;
    private final List<Integer> children;

    public BPlusTreeInternalNode(int pageId, int parentId, DataType keyType) {
        this.pageId = pageId;
        this.parentId = parentId;
        this.keyType = keyType;
        this.keys = new ArrayList<>();
        this.children = new ArrayList<>();
    }

    public int getPageId() { return pageId; }
    public int getParentId() { return parentId; }
    public void setParentId(int parentId) { this.parentId = parentId; }
    public DataType getKeyType() { return keyType; }
    public List<IndexKey> getKeys() { return keys; }
    public List<Integer> getChildren() { return children; }
    public int getKeyCount() { return keys.size(); }

    /**
     * Finds the child page ID that could contain the given key.
     */
    public int findChildPageId(IndexKey key) {
        int idx = Collections.binarySearch(keys, key);
        if (idx >= 0) {
            return children.get(idx + 1);
        } else {
            int insertionPoint = -(idx + 1);
            return children.get(insertionPoint);
        }
    }

    /**
     * Finds the LEFTMOST child page that can contain {@code key}.
     *
     * <p>With duplicate keys, entries equal to a separator may live in the child to
     * the LEFT of that separator as well as to its right (a leaf split can cut a run of
     * duplicates in two). Lookups, range scans and deletes must therefore start at the
     * first separator that is {@code >= key} and walk the leaf chain rightwards;
     * {@link #findChildPageId} (right-biased) is only correct for choosing where to insert.
     */
    public int findLeftmostChildPageId(IndexKey key) {
        int lo = 0, hi = keys.size();
        while (lo < hi) {               // lower bound: first index with keys[i] >= key
            int mid = (lo + hi) >>> 1;
            if (keys.get(mid).compareTo(key) < 0) lo = mid + 1; else hi = mid;
        }
        return children.get(lo);
    }

    /**
     * Inserts a key and its right-child pointer in sorted order.
     */
    /**
     * Inserts {@code key} and its right child immediately AFTER the child that was split.
     *
     * <p>Positioning by key alone is ambiguous when equal separators exist (duplicate keys): the new
     * child could land among them out of leaf-chain order, and lower-bound lookups would then skip
     * leaves. Anchoring on the split child keeps parent order == leaf-chain order.
     */
    public void insertChildAfter(int leftChildPageId, IndexKey key, int rightChildPageId) {
        int j = children.indexOf(leftChildPageId);
        if (j < 0) {
            insertChild(key, rightChildPageId);
            return;
        }
        keys.add(j, key);
        children.add(j + 1, rightChildPageId);
    }

    public void insertChild(IndexKey key, int rightChildPageId) {
        int idx = Collections.binarySearch(keys, key);
        if (idx < 0) {
            idx = -(idx + 1);
        }
        keys.add(idx, key);
        children.add(idx + 1, rightChildPageId);
    }

    /**
     * Serializes this internal node into the given Page buffer.
     */
    public void serialize(Page page) {
        ByteBuffer buf = ByteBuffer.wrap(page.getData());
        buf.put(BPlusTreeNodeType.INTERNAL.getCode()); // 1B
        buf.putShort((short) keys.size());             // 2B
        buf.putInt(parentId);                          // 4B
        buf.putInt(children.isEmpty() ? -1 : children.get(0)); // 4B leftmost child

        for (int i = 0; i < keys.size(); i++) {
            buf.put(keys.get(i).toBytes());
            buf.putInt(children.get(i + 1));
        }
    }

    /**
     * Deserializes an internal node from a Page.
     */
    public static BPlusTreeInternalNode deserialize(Page page, DataType keyType) {
        ByteBuffer buf = ByteBuffer.wrap(page.getData());
        byte typeCode = buf.get();
        if (typeCode != BPlusTreeNodeType.INTERNAL.getCode()) {
            throw new IllegalArgumentException("Page " + page.getPageId() + " is not an internal node (type=" + typeCode + ")");
        }

        int count = Short.toUnsignedInt(buf.getShort());
        int parentId = buf.getInt();
        int leftChild = buf.getInt();

        BPlusTreeInternalNode node = new BPlusTreeInternalNode(page.getPageId(), parentId, keyType);
        node.children.add(leftChild);

        for (int i = 0; i < count; i++) {
            IndexKey key = IndexKey.fromByteBuffer(buf, keyType);
            int childPageId = buf.getInt();
            node.keys.add(key);
            node.children.add(childPageId);
        }
        return node;
    }
}
