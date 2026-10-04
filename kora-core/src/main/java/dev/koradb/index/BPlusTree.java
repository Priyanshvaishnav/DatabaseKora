package dev.koradb.index;

import dev.koradb.buffer.BufferFrame;
import dev.koradb.buffer.BufferPool;
import dev.koradb.exception.StorageException;
import dev.koradb.sql.ast.DataType;
import dev.koradb.storage.RecordId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * On-disk B+ Tree index backed by a {@link BufferPool}.
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li>Page 0: Index Header (Magic "KORA_BPT", rootPageId, keyType, degree, count)</li>
 *   <li>Page 1+: Leaf and Internal B+ Tree nodes</li>
 * </ul>
 *
 * <p>Supports logarithmic point searches, fast range scans via leaf sibling pointers,
 * dynamic node splits, and automatic tree height expansion.
 */
public final class BPlusTree implements Index {

    private static final Logger log = LoggerFactory.getLogger(BPlusTree.class);
    private static final byte[] MAGIC = "KORA_BPT".getBytes(StandardCharsets.US_ASCII);

    public static final int DEFAULT_MAX_DEGREE = 64;

    private final String name;
    private final String tableName;
    private final String columnName;
    private final DataType keyType;
    private final boolean unique;
    private final BufferPool bufferPool;
    private final int maxDegree;

    private int rootPageId;
    private int entryCount;

    /**
     * Opens or initializes a B+ Tree index using default node degree.
     */
    public BPlusTree(String name, String tableName, String columnName, DataType keyType,
                     boolean unique, BufferPool bufferPool) {
        this(name, tableName, columnName, keyType, unique, bufferPool, DEFAULT_MAX_DEGREE);
    }

    /**
     * Opens or initializes a B+ Tree index with a configurable maximum degree.
     */
    public BPlusTree(String name, String tableName, String columnName, DataType keyType,
                     boolean unique, BufferPool bufferPool, int maxDegree) {
        this.name = name;
        this.tableName = tableName;
        this.columnName = columnName;
        this.keyType = keyType;
        this.unique = unique;
        this.bufferPool = bufferPool;
        this.maxDegree = Math.max(3, maxDegree);

        initOrLoad();
    }

    private void initOrLoad() {
        int pageCount = bufferPool.getDiskManager().pageCount();
        if (pageCount == 0) {
            // Brand new index file: Allocate Page 0 (Header) and Page 1 (Initial Root Leaf)
            BufferFrame headerFrame = bufferPool.allocatePage();
            BufferFrame rootLeafFrame = bufferPool.allocatePage();

            this.rootPageId = rootLeafFrame.getPage().getPageId();
            this.entryCount = 0;

            try {
                // Initialize root leaf node
                BPlusTreeLeafNode rootLeaf = new BPlusTreeLeafNode(rootPageId, -1, -1, -1, keyType);
                rootLeaf.serialize(rootLeafFrame.getPage());
                bufferPool.markDirty(rootLeafFrame);
            } finally {
                bufferPool.unpin(rootLeafFrame);
            }

            try {
                // Write Page 0 Header
                ByteBuffer buf = ByteBuffer.wrap(headerFrame.getPage().getData());
                buf.put(MAGIC);
                buf.putInt(rootPageId);
                buf.putInt(keyType.ordinal());
                buf.putInt(maxDegree);
                buf.putInt(entryCount);
                bufferPool.markDirty(headerFrame);
            } finally {
                bufferPool.unpin(headerFrame);
            }

            log.debug("Initialized new B+ Tree index {} on {}({}) with rootPageId={}",
                    name, tableName, columnName, rootPageId);
        } else {
            // Existing index: read Header Page 0
            BufferFrame headerFrame = bufferPool.pin(0);
            try {
                ByteBuffer buf = ByteBuffer.wrap(headerFrame.getPage().getData());
                byte[] magic = new byte[8];
                buf.get(magic);
                if (!java.util.Arrays.equals(magic, MAGIC)) {
                    throw new StorageException("Corrupt index file: invalid magic header for " + name);
                }
                this.rootPageId = buf.getInt();
                int typeOrdinal = buf.getInt();
                int storedDegree = buf.getInt();
                this.entryCount = buf.getInt();
                log.debug("Opened existing B+ Tree index {} (rootPageId={}, count={})",
                        name, rootPageId, entryCount);
            } finally {
                bufferPool.unpin(headerFrame);
            }
        }
    }

    private void writeHeader() {
        BufferFrame headerFrame = bufferPool.pin(0);
        try {
            ByteBuffer buf = ByteBuffer.wrap(headerFrame.getPage().getData());
            buf.put(MAGIC);
            buf.putInt(rootPageId);
            buf.putInt(keyType.ordinal());
            buf.putInt(maxDegree);
            buf.putInt(entryCount);
            bufferPool.markDirty(headerFrame);
        } finally {
            bufferPool.unpin(headerFrame);
        }
    }

    @Override
    public String getName() { return name; }

    @Override
    public String getTableName() { return tableName; }

    @Override
    public String getColumnName() { return columnName; }

    @Override
    public DataType getKeyType() { return keyType; }

    @Override
    public boolean isUnique() { return unique; }

    public int getRootPageId() { return rootPageId; }

    public int size() { return entryCount; }

    // =========================================================================
    // SEARCH
    // =========================================================================

    @Override
    public Optional<RecordId> search(IndexKey key) {
        int leafPid = findLeafPageId(key);
        BufferFrame frame = bufferPool.pin(leafPid);
        try {
            BPlusTreeLeafNode leaf = BPlusTreeLeafNode.deserialize(frame.getPage(), keyType);
            return leaf.find(key);
        } finally {
            bufferPool.unpin(frame);
        }
    }

    @Override
    public List<RecordId> searchAll(IndexKey key) {
        return rangeScan(key, key, true, true);
    }

    // =========================================================================
    // RANGE SCAN
    // =========================================================================

    @Override
    public List<RecordId> rangeScan(IndexKey low, IndexKey high, boolean lowInclusive, boolean highInclusive) {
        List<RecordId> results = new ArrayList<>();
        int currLeafPid;

        if (low == null) {
            currLeafPid = findLeftmostLeafPageId();
        } else {
            currLeafPid = findLeafPageId(low);
        }

        while (currLeafPid != -1) {
            BufferFrame frame = bufferPool.pin(currLeafPid);
            int nextPid;
            try {
                BPlusTreeLeafNode leaf = BPlusTreeLeafNode.deserialize(frame.getPage(), keyType);
                nextPid = leaf.getNextPageId();

                List<IndexKey> keys = leaf.getKeys();
                List<RecordId> values = leaf.getValues();

                for (int i = 0; i < keys.size(); i++) {
                    IndexKey k = keys.get(i);

                    // Check low bound
                    if (low != null) {
                        int cmpLow = k.compareTo(low);
                        if (cmpLow < 0 || (!lowInclusive && cmpLow == 0)) {
                            continue;
                        }
                    }

                    // Check high bound
                    if (high != null) {
                        int cmpHigh = k.compareTo(high);
                        if (cmpHigh > 0 || (!highInclusive && cmpHigh == 0)) {
                            return results; // Range scan complete
                        }
                    }

                    results.add(values.get(i));
                }
            } finally {
                bufferPool.unpin(frame);
            }

            currLeafPid = nextPid;
        }

        return results;
    }

    // =========================================================================
    // INSERT
    // =========================================================================

    @Override
    public void insert(IndexKey key, RecordId rid) {
        if (unique) {
            Optional<RecordId> existing = search(key);
            if (existing.isPresent()) {
                throw new StorageException("Unique index violation: duplicate key " + key + " in index " + name);
            }
        }

        int leafPid = findLeafPageId(key);
        BufferFrame leafFrame = bufferPool.pin(leafPid);
        BPlusTreeLeafNode leaf;
        try {
            leaf = BPlusTreeLeafNode.deserialize(leafFrame.getPage(), keyType);
            leaf.insert(key, rid);
            leaf.serialize(leafFrame.getPage());
            bufferPool.markDirty(leafFrame);

            if (leaf.getKeyCount() <= maxDegree) {
                entryCount++;
                writeHeader();
                return;
            }
        } finally {
            bufferPool.unpin(leafFrame);
        }

        // Leaf is overfull: split leaf
        splitLeaf(leafPid);
        entryCount++;
        writeHeader();
    }

    private void splitLeaf(int leafPid) {
        BufferFrame leftFrame = bufferPool.pin(leafPid);
        BufferFrame rightFrame = bufferPool.allocatePage();
        int rightPid = rightFrame.getPage().getPageId();

        IndexKey splitKey;
        int parentId;

        try {
            BPlusTreeLeafNode left = BPlusTreeLeafNode.deserialize(leftFrame.getPage(), keyType);
            parentId = left.getParentId();
            int oldNext = left.getNextPageId();

            BPlusTreeLeafNode right = new BPlusTreeLeafNode(
                    rightPid, parentId, left.getPageId(), oldNext, keyType);

            int totalKeys = left.getKeyCount();
            int mid = totalKeys / 2;

            List<IndexKey> rightKeys = new ArrayList<>(left.getKeys().subList(mid, totalKeys));
            List<RecordId> rightVals = new ArrayList<>(left.getValues().subList(mid, totalKeys));

            // Truncate left
            while (left.getKeys().size() > mid) {
                left.getKeys().remove(left.getKeys().size() - 1);
                left.getValues().remove(left.getValues().size() - 1);
            }

            right.getKeys().addAll(rightKeys);
            right.getValues().addAll(rightVals);

            left.setNextPageId(rightPid);
            splitKey = right.getKeys().get(0);

            left.serialize(leftFrame.getPage());
            right.serialize(rightFrame.getPage());
            bufferPool.markDirty(leftFrame);
            bufferPool.markDirty(rightFrame);

            // Update sibling link of old next
            if (oldNext != -1) {
                BufferFrame nextFrame = bufferPool.pin(oldNext);
                try {
                    BPlusTreeLeafNode oldNextNode = BPlusTreeLeafNode.deserialize(nextFrame.getPage(), keyType);
                    oldNextNode.setPrevPageId(rightPid);
                    oldNextNode.serialize(nextFrame.getPage());
                    bufferPool.markDirty(nextFrame);
                } finally {
                    bufferPool.unpin(nextFrame);
                }
            }
        } finally {
            bufferPool.unpin(leftFrame);
            bufferPool.unpin(rightFrame);
        }

        insertIntoParent(leafPid, parentId, splitKey, rightPid);
    }

    private void insertIntoParent(int leftChildPid, int parentPid, IndexKey splitKey, int rightChildPid) {
        if (parentPid == -1 || leftChildPid == rootPageId) {
            // Allocate new root internal node
            BufferFrame newRootFrame = bufferPool.allocatePage();
            int newRootPid = newRootFrame.getPage().getPageId();
            try {
                BPlusTreeInternalNode newRoot = new BPlusTreeInternalNode(
                        newRootPid, -1, keyType);
                newRoot.getChildren().add(leftChildPid);
                newRoot.getKeys().add(splitKey);
                newRoot.getChildren().add(rightChildPid);

                newRoot.serialize(newRootFrame.getPage());
                bufferPool.markDirty(newRootFrame);

                this.rootPageId = newRootPid;
                writeHeader();
            } finally {
                bufferPool.unpin(newRootFrame);
            }

            // Update parent IDs of the two children
            updateParentId(leftChildPid, rootPageId);
            updateParentId(rightChildPid, rootPageId);
            return;
        }

        // Insert into existing parent
        BufferFrame parentFrame = bufferPool.pin(parentPid);
        BPlusTreeInternalNode parent;
        try {
            parent = BPlusTreeInternalNode.deserialize(parentFrame.getPage(), keyType);
            parent.insertChild(splitKey, rightChildPid);
            parent.serialize(parentFrame.getPage());
            bufferPool.markDirty(parentFrame);
            updateParentId(rightChildPid, parentPid);

            if (parent.getKeyCount() <= maxDegree) {
                return;
            }
        } finally {
            bufferPool.unpin(parentFrame);
        }

        // Parent internal node is overfull: split internal node
        splitInternal(parentPid);
    }

    private void splitInternal(int internalPid) {
        BufferFrame leftFrame = bufferPool.pin(internalPid);
        BufferFrame rightFrame = bufferPool.allocatePage();
        int rightPid = rightFrame.getPage().getPageId();

        IndexKey middleKey;
        int parentId;

        try {
            BPlusTreeInternalNode left = BPlusTreeInternalNode.deserialize(leftFrame.getPage(), keyType);
            parentId = left.getParentId();

            BPlusTreeInternalNode right = new BPlusTreeInternalNode(
                    rightPid, parentId, keyType);

            int totalKeys = left.getKeyCount();
            int mid = totalKeys / 2;

            // In an internal node, middle key is pushed UP to parent, not kept in either node
            middleKey = left.getKeys().get(mid);

            List<IndexKey> rightKeys = new ArrayList<>(left.getKeys().subList(mid + 1, totalKeys));
            List<Integer> rightChildren = new ArrayList<>(left.getChildren().subList(mid + 1, totalKeys + 1));

            // Truncate left
            while (left.getKeys().size() > mid) {
                left.getKeys().remove(left.getKeys().size() - 1);
            }
            while (left.getChildren().size() > mid + 1) {
                left.getChildren().remove(left.getChildren().size() - 1);
            }

            right.getKeys().addAll(rightKeys);
            right.getChildren().addAll(rightChildren);

            left.serialize(leftFrame.getPage());
            right.serialize(rightFrame.getPage());
            bufferPool.markDirty(leftFrame);
            bufferPool.markDirty(rightFrame);

            // Update parent IDs of all children moved to right internal node
            for (int childPid : right.getChildren()) {
                updateParentId(childPid, rightPid);
            }
        } finally {
            bufferPool.unpin(leftFrame);
            bufferPool.unpin(rightFrame);
        }

        insertIntoParent(internalPid, parentId, middleKey, rightPid);
    }

    private void updateParentId(int pageId, int newParentId) {
        BufferFrame frame = bufferPool.pin(pageId);
        try {
            byte type = frame.getPage().readByte(0);
            if (type == BPlusTreeNodeType.LEAF.getCode()) {
                BPlusTreeLeafNode node = BPlusTreeLeafNode.deserialize(frame.getPage(), keyType);
                node.setParentId(newParentId);
                node.serialize(frame.getPage());
            } else {
                BPlusTreeInternalNode node = BPlusTreeInternalNode.deserialize(frame.getPage(), keyType);
                node.setParentId(newParentId);
                node.serialize(frame.getPage());
            }
            bufferPool.markDirty(frame);
        } finally {
            bufferPool.unpin(frame);
        }
    }

    // =========================================================================
    // DELETE
    // =========================================================================

    @Override
    public boolean delete(IndexKey key, RecordId rid) {
        int leafPid = findLeafPageId(key);
        BufferFrame frame = bufferPool.pin(leafPid);
        try {
            BPlusTreeLeafNode leaf = BPlusTreeLeafNode.deserialize(frame.getPage(), keyType);
            boolean removed = leaf.delete(key, rid);
            if (removed) {
                leaf.serialize(frame.getPage());
                bufferPool.markDirty(frame);
                entryCount--;
                writeHeader();
            }
            return removed;
        } finally {
            bufferPool.unpin(frame);
        }
    }

    // =========================================================================
    // TRAVERSAL HELPERS
    // =========================================================================

    private int findLeafPageId(IndexKey key) {
        int currPid = rootPageId;
        while (true) {
            BufferFrame frame = bufferPool.pin(currPid);
            byte nodeType;
            int nextPid;
            try {
                nodeType = frame.getPage().readByte(0);
                if (nodeType == BPlusTreeNodeType.LEAF.getCode()) {
                    return currPid;
                }
                BPlusTreeInternalNode internal = BPlusTreeInternalNode.deserialize(frame.getPage(), keyType);
                nextPid = internal.findChildPageId(key);
            } finally {
                bufferPool.unpin(frame);
            }
            currPid = nextPid;
        }
    }

    private int findLeftmostLeafPageId() {
        int currPid = rootPageId;
        while (true) {
            BufferFrame frame = bufferPool.pin(currPid);
            byte nodeType;
            int nextPid;
            try {
                nodeType = frame.getPage().readByte(0);
                if (nodeType == BPlusTreeNodeType.LEAF.getCode()) {
                    return currPid;
                }
                BPlusTreeInternalNode internal = BPlusTreeInternalNode.deserialize(frame.getPage(), keyType);
                nextPid = internal.getChildren().get(0);
            } finally {
                bufferPool.unpin(frame);
            }
            currPid = nextPid;
        }
    }

    @Override
    public void flush() {
        bufferPool.flushAll();
    }

    @Override
    public void close() {
        flush();
        bufferPool.close();
    }
}
