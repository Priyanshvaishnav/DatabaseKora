# Write-Ahead Logging (WAL) & Checkpointing — Design Document

## 1. What Problem Does It Solve?

In database systems using the **Steal / No-Force** buffer management policy:
1. **No-Force (Commit Latency)**: When a transaction commits, dirty data pages in the buffer pool are not immediately forced to disk (doing random disk I/O per transaction on 4KB pages would destroy throughput). However, without forcing pages to disk, if the server crashes or loses power, committed updates resident only in RAM would be lost (**Durability violation**).
2. **Steal (Memory Constraints)**: When the buffer pool runs out of clean frames, it must evict dirty pages to disk before transactions commit (**Steal policy**). If an active transaction has dirtied a page and that page is evicted to disk, but the transaction later aborts or the database crashes, disk contains uncommitted changes (**Atomicity violation**).
3. **The WAL Invariant**: To reconcile Steal and No-Force:
   - **Write-Ahead Rule**: Before any dirty page is written to disk, all log records describing modifications up to that page's state (`pageLSN <= flushedLSN`) must be persisted and fsynced to disk.
   - **Force-Log Rule**: When a transaction commits, its `COMMIT` log record must be persisted to disk before returning success to the client.

**Milestone 8** implements a complete **Write-Ahead Logging (WAL)** engine, **Group Commit**, **Buffer Pool WAL Invariant Enforcement**, and **ARIES-style Periodic Checkpointing** in KoraDB.

---

## 2. Architecture & Pipeline

```
                     SQL Execution / Transactions
                                  │
                                  ▼
                        TransactionManager
                         - beginTransaction()  ───► writes BEGIN log record
                         - commitTransaction() ───► writes COMMIT log record & fsync
                         - abortTransaction()  ───► writes ABORT log record
                                  │
                                  ▼
                         Table Operations (DML)
                         - insertRow / updateRow / deleteRow
                                  │
                                  ├──────────────────────────────┐
                                  ▼                              ▼
                         BufferPool & Page              LogManager (kora.wal)
                         - Stamps pageLSN               - Append-only ring buffer
                         - Tracks recLSN (DPT)          - Monotonic LSN allocation
                         - Enforces WAL Invariant:      - Group Commit (batched fsync)
                           pageLSN <= flushedLSN
                                  │                              ▲
                                  ▼                              │
                             Disk (Data)                    Disk (WAL)
                             (*.kdb files)                  (kora.wal)
                                  ▲                              ▲
                                  │                              │
                                  └────── CheckpointManager ─────┘
                                          - CheckpointBegin
                                          - Flush dirty frames
                                          - Snapshot Active Txn Table
                                          - Snapshot Dirty Page Table
                                          - CheckpointEnd & WAL fsync
```

---

## 3. Log Record Types & Binary Framing

Every log record is encoded with fixed framing, validation magic, and CRC32 checksums:

```
┌──────────────┬──────────────┬──────────────┬──────────────┬──────────────┬──────────────┬──────────────┬──────────────┐
│ Length (4B)  │ Magic (4B)   │ LSN (8B)     │ PrevLSN (8B) │ TxnID (8B)   │ Type (1B)    │ Payload (NB) │ CRC32 (4B)   │
└──────────────┴──────────────┴──────────────┴──────────────┴──────────────┴──────────────┴──────────────┴──────────────┘
```

- **Length**: Total record byte length (including framing, magic, header, payload, and CRC32).
- **Magic**: Constant `0x57414C52` (`WALR`) to protect against misaligned reads or file corruption.
- **LSN**: Monotonically increasing Log Sequence Number indicating the byte offset in the WAL file.
- **PrevLSN**: Backward pointer linking the current transaction's log records for rapid rollback / undo traversal.
- **TxnID**: Unique transaction identifier (or 0 for non-transactional system records).
- **Type**: 1-byte opcode identifier:
  - `0x01` (`BEGIN`): Transaction start.
  - `0x02` (`UPDATE`): Physiological page update (stores `tableName`, `pageId`, `slotIndex`, `beforeImage`, `afterImage`).
  - `0x03` (`COMMIT`): Transaction commit marker.
  - `0x04` (`ABORT`): Transaction abort marker.
  - `0x05` (`CHECKPOINT_BEGIN`): Checkpoint start timestamp.
  - `0x06` (`CHECKPOINT_END`): Checkpoint completion storing the Active Transaction Table and Dirty Page Table.
- **CRC32**: Checksum covering all bytes from `Magic` through the end of `Payload`.

---

## 4. High-Performance Group Commit

Individual `fsync()` system calls to magnetic disks or SSDs incur substantial latency (0.5ms–5ms per sync). Under high concurrency, forcing disk I/O per transaction serialized by locks would bottleneck the database.

KoraDB's `LogManager` uses **Group Commit**:
1. Multiple threads append their records concurrently into the in-memory log buffer (`ByteBuffer`) using atomic sequence allocation (`nextLsn.getAndAdd(len)`).
2. When a thread invokes `flush(targetLsn)`, if `targetLsn < flushedLsn`, it returns immediately with zero physical I/O.
3. If `targetLsn >= flushedLsn`, one thread becomes the "flusher" while other concurrent flush requests wait on an internal monitor.
4. The flusher transfers all buffered log bytes into the OS file channel and executes a single `channel.force(false)` (`fsync`).
5. Upon completion, `flushedLsn` advances to `nextLsn` and wakes up all waiting threads. All transactions whose log records were included in the flushed range are committed together in a single disk I/O.

---

## 5. The WAL Invariant in BufferPool

To maintain the **Write-Ahead Logging Invariant**:
- The slotted page header was expanded from 16 bytes to 24 bytes in `StorageConfig.PAGE_HEADER_SIZE`.
- Offset 16 stores the 8-byte `pageLSN`, representing the LSN of the most recent log record that updated the page.
- Whenever `TableFile` modifies a page (`insertRow`, `updateRow`, `deleteRow`), it writes an `UpdateLogRecord`, sets `page.setPageLsn(lsn)`, and marks the frame dirty.
- In `BufferPool.flushFrameToDisk(BufferFrame frame)`:
  ```java
  long pageLsn = page.getPageLsn();
  if (logManager != null && pageLsn > 0 && pageLsn >= logManager.getFlushedLsn()) {
      logManager.flush(pageLsn);
  }
  diskManager.write(page);
  frame.clearDirty();
  ```
This ensures that dirty pages **can never reach disk** before the corresponding WAL records are safely synced.

---

## 6. ARIES-Style Checkpointing

Checkpointing truncates recovery time during crash restart by capturing snapshots of volatile state.

`CheckpointManager` coordinates the following sequence:
1. **Append `CheckpointBeginRecord`**: Stamped with the current epoch timestamp.
2. **Buffer Flush & DPT Collection**: Flushes all dirty frames across open tables to disk (triggering WAL syncs where necessary). Gathers the **Dirty Page Table (DPT)** (`pageId -> recLSN`).
3. **Active Transaction Snapshot**: Collects the **Transaction Table (ATT)** (`txnId -> prevLSN`) of all currently active transactions.
4. **Append `CheckpointEndRecord`**: Writes the ATT and DPT to the WAL.
5. **Force WAL Flush**: Fsyncs the checkpoint end record so recovery knows the safe boundary.

During Milestone 9 (ARIES Crash Recovery), the analysis pass starts from the checkpoint record, reconstructing precisely which transactions were active and which pages were dirty at the time of failure.

---

## 7. Storage Structure & Multi-Database Isolation

WAL logs are isolated per database inside each database directory:
```
dataDir/
  company/
    catalog.meta
    kora.wal        <--- WAL log file for 'company'
    employees.kdb
    departments.kdb
  finance/
    catalog.meta
    kora.wal        <--- WAL log file for 'finance'
    accounts.kdb
```
When `USE <db>` is executed, `DiskCatalog` dynamically binds the active `LogManager` to the database, ensuring seamless isolation, clean teardowns, and zero resource leaks across restarts.
