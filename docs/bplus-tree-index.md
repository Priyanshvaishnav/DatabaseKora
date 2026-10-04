# B+ Tree Indexing Engine — Design Document

## 1. What Problem Does It Solve?

In relational database systems, full table scans scale linearly with the table size ($O(N)$). When tables contain thousands or millions of rows, scanning every page to find a single row matching `WHERE id = 42` creates massive disk I/O bottlenecks.

The **B+ Tree Indexing Engine** in KoraDB provides:
- **$O(\log_B N)$ point lookups** for equality queries.
- **Ordered range scans** via linked leaf nodes (`nextPageId` and `prevPageId`).
- **Uniqueness enforcement** (`UNIQUE INDEX`) preventing duplicate entries.
- **Durability and buffer-managed caching** backed by KoraDB's `BufferPool`.

---

## 2. Architecture & Design

KoraDB's B+ Tree is an on-disk, multi-way search tree where all data references reside strictly in the leaf nodes, while internal nodes store routing keys and child page pointers.

```
                         [ Internal Node: Root ]
                             [ K1 | K2 | K3 ]
                           /      |      |      \
                        P0       P1      P2      P3
                       /          |       |        \
            [ Internal ]     [ Internal ]  ...
                /    \
            Leaf0 <---> Leaf1 <---> Leaf2 <---> Leaf3 (Doubly-Linked)
          [K, RID]   [K, RID]   [K, RID]   [K, RID]
```

### 2.1 Node Types (`BPlusTreeNodeType`)
- **LEAF (type = 1)**: Stores an array of `(IndexKey, RecordId)` pairs. Contains `prevPageId` and `nextPageId` references for bidirectional sequential range scanning.
- **INTERNAL (type = 2)**: Stores routing keys $[K_1, K_2, \dots, K_m]$ and child page pointers $[P_0, P_1, \dots, P_m]$ such that all keys in subtree $P_i$ satisfy $K_i \le K < K_{i+1}$.

### 2.2 Header Page (Page 0)
Every index file (`<index_name>.idx`) reserves Page 0 as its metadata header:
- Magic bytes (`KORA_IDX`)
- `rootPageId` (pointer to active root page)
- `keyType` (SQL `DataType` ordinal)
- `maxDegree` (maximum number of keys per node)
- `entryCount` (total live index records)

---

## 3. Index Keys (`IndexKey`)

The `IndexKey` class represents a strongly-typed comparable key. It supports:
- `INT` (4-byte integer)
- `BIGINT` (8-byte long)
- `DOUBLE` (8-byte IEEE 754 float)
- `VARCHAR` (length-prefixed UTF-8 string)
- `BOOLEAN` (1-byte flag)
- `TIMESTAMP` (16-byte packed date-time)

Keys support nullability, strict type checking, and standard binary serialization to/from byte buffers for storage inside slotted pages.

---

## 4. Tree Operations

### 4.1 Search
1. Traverse down from `rootPageId` using internal node binary search until the matching leaf page is found.
2. Binary search within the leaf node for the matching `IndexKey`.
3. Return the associated `RecordId(pageId, slotIndex)` or `Optional.empty()`.

### 4.2 Range Scan
1. Locate the starting leaf page for `lowKey` (or the leftmost leaf if unbounded).
2. Iterate sequentially across entries, following `nextPageId` pointers across leaf pages until reaching `highKey` or end of tree.

### 4.3 Insertion & Node Splitting
1. Find target leaf node.
2. If the leaf has available capacity, insert in sorted position, mark page dirty, and update header.
3. If the leaf is full:
   - Split the leaf in half into a new right leaf page.
   - Link siblings: `left.nextPageId = right`, `right.prevPageId = left`.
   - Propagate the middle split key up to the parent internal node.
   - If the parent is also full, recursively split internal nodes.
   - If the root splits, allocate a new root node, increasing the tree height by 1.

### 4.4 Deletion
1. Find leaf page containing the target key and record ID.
2. Remove the entry, shift remaining entries, mark dirty, and decrement entry count.

---

## 5. SQL Engine & Storage Integration

### 5.1 SQL Syntax
- **Create Index**: `CREATE [UNIQUE] INDEX <index_name> ON <table_name> ( <column_name> )`
- **Drop Index**: `DROP INDEX <index_name> [ON <table_name>]`

### 5.2 Executor & Optimizer Integration
- **Point Lookups**: `StatementExecutor` checks for equality predicates (`WHERE col = val`) against available indexes on `<table_name>`. If an index is present, it executes an index lookup to directly fetch matching `RecordId`s, falling back to full table scan only when no index is applicable.
- **Write Synchronization**:
  - `INSERT`: Inserts row into `TableFile` and inserts `(key, rid)` into all table indexes.
  - `UPDATE`: Updates row in `TableFile`, updating old and new keys in indexed columns.
  - `DELETE`: Marks slot deleted and cleans up index entries.
- **Persistence**: Index metadata is stored in `_indexes.meta`, and index trees survive JVM restarts.

---

## 6. Test Verification

Milestone 4 is verified with 228 passing tests across unit and integration suites:
- `IndexKeyTest`: Type serialization, comparisons, string & numeric boundaries.
- `BPlusTreeTest`: 1,000+ insertions with deep tree splits, multi-page range scans, uniqueness, restart durability.
- `ParserTest`: AST validation for `CREATE INDEX` and `DROP INDEX`.
- `IndexIntegrationTest`: End-to-end SQL verification covering index creation on populated tables, auto-updates on INSERT/UPDATE/DELETE, and restart survival.
