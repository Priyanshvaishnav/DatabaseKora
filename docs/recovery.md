# ARIES Crash Recovery — Design Document

## 1. What Problem Does It Solve?

In database management systems employing a **Steal / No-Force** buffer management policy:
1. **Committed updates can be lost on crash (No-Force)**: Because dirty pages in the buffer pool are not forced to disk at commit time, an unexpected crash or power failure leaves committed modifications resident only in volatile RAM. Recovery must reapply these operations (**Durability**).
2. **Uncommitted updates can be present on disk (Steal)**: When buffer pool frames are exhausted, the buffer manager may evict dirty pages to disk before the corresponding transactions have committed. If a crash occurs, disk contains partial, uncommitted state. Recovery must undo these operations (**Atomicity**).
3. **Crash During Recovery**: If the database crashes *while recovering*, recovery must be **idempotent** — running recovery multiple times must produce the exact same clean state without duplicating updates or undos.

KoraDB implements the industry-standard **ARIES (Algorithm for Recovery and Isolation Exploiting Semantics)** algorithm invented by C. Mohan et al. at IBM.

---

## 2. ARIES Architecture & 3-Phase Pipeline

```
                                  [ Crash Occurs ]
                                         │
                                         ▼
                                   kora.wal File
                                         │
 ┌───────────────────────────────────────┴───────────────────────────────────────┐
 │                                                                               │
 │  Phase 1: ANALYSIS PASS                                                       │
 │  • Scans forward from latest CheckpointBegin to log EOF                       │
 │  • Reconstructs:                                                              │
 │      - Active Transaction Table (ATT)                                         │
 │      - Dirty Page Table (DPT) with recLSN                                     │
 │  • Partitions transactions:                                                   │
 │      - Winners: COMMITTED transactions                                        │
 │      - Losers:  ACTIVE / ABORTED transactions needing rollback                │
 │  • Computes Redo Start LSN: min(recLSN across all dirty pages in DPT)         │
 │                                                                               │
 └───────────────────────────────────────┬───────────────────────────────────────┘
                                         │
                                         ▼
 ┌───────────────────────────────────────────────────────────────────────────────┐
 │                                                                               │
 │  Phase 2: REDO PASS ("Repeating History")                                     │
 │  • Scans forward from redoStartLSN to log EOF                                 │
 │  • Replays ALL logged operations (UpdateLogRecord, ClrLogRecord)              │
 │  • Re-establishes exact state of all pages at time of crash                    │
 │  • Skips redundant I/O:                                                       │
 │      1. If page not in DPT ──► SKIP                                           │
 │      2. If recordLSN < recLSN ──► SKIP                                        │
 │      3. If pageLSN on disk >= recordLSN ──► SKIP                              │
 │                                                                               │
 └───────────────────────────────────────┬───────────────────────────────────────┘
                                         │
                                         ▼
 ┌───────────────────────────────────────────────────────────────────────────────┐
 │                                                                               │
 │  Phase 3: UNDO PASS                                                           │
 │  • Rolls back all loser transactions backward in time across all losers       │
 │  • Uses max-heap PriorityQueue ordered by LSN (highest LSN processed first)   │
 │  • For each update:                                                           │
 │      - Restores before-image (or deletes inserted record)                     │
 │      - Writes Compensation Log Record (CLR) with undoNextLSN pointer          │
 │      - Advances to update.prevLSN                                             │
 │  • When an existing CLR is encountered:                                       │
 │      - Never undoes the CLR; follows clr.undoNextLSN directly                 │
 │  • Writes END log record for each completed loser transaction                 │
 │  • Flushes buffer pool and WAL to disk                                        │
 │                                                                               │
 └───────────────────────────────────────┬───────────────────────────────────────┘
                                         │
                                         ▼
                           [ Database Ready for Traffic ]
```

---

## 3. Phase 1: Analysis Pass

The **Analysis Pass** inspects the WAL to reconstruct the database's mental model at the time of failure:

1. **Locating Checkpoint**:
   - Scans the log to find the latest completed checkpoint (`CheckpointBeginRecord` and `CheckpointEndRecord`).
   - If found, seeds the **Active Transaction Table (ATT)** and **Dirty Page Table (DPT)** from the checkpoint snapshot.
   - Sets analysis scan starting point to `CheckpointBeginRecord.lsn`.
   - If no checkpoint exists, starts from the WAL header (`WAL_HEADER_SIZE = 16`).

2. **Reconstructing ATT & DPT**:
   - `BEGIN`: registers transaction as `ACTIVE`.
   - `UPDATE`: updates `lastLSN = lsn` and adds page to DPT with `recLSN = lsn` (if not already dirty).
   - `CLR`: updates `lastLSN = lsn`, sets `undoNextLSN = clr.undoNextLSN`, and adds page to DPT.
   - `COMMIT`: marks transaction status as `COMMITTED` (Winner).
   - `ABORT`: marks transaction status as `ABORTED` (Loser).
   - `END`: removes transaction from ATT.

3. **Determining `redoStartLSN`**:
   - `redoStartLSN = min(recLSN in DPT)`.
   - Any page modified before `redoStartLSN` is guaranteed to have already been written to disk.

---

## 4. Phase 2: Redo Pass ("Repeating History")

The **Redo Pass** starts from `redoStartLSN` and scans forward to the end of the log. ARIES mandates **repeating history**: both committed (winner) and uncommitted (loser) transactions are redone.

### Redo Optimization Checks
Before loading and modifying a disk page, Redo performs three checks to avoid unnecessary disk I/O:
1. **DPT Check**: If the target `(tableName, pageId)` is not present in the Dirty Page Table, the page was clean in memory at crash time and already on disk. Skip redo.
2. **recLSN Check**: If `record.lsn < recLSN`, the page on disk already reflects this log record. Skip redo.
3. **pageLSN Check**: Read the page from disk/buffer pool. If `page.getPageLsn() >= record.lsn`, the modification was already written before crash. Skip redo.

If none of the skip conditions apply:
- The `afterImage` is written into the slotted page (`sp.updateRecord(...)` or `sp.deleteRecord(...)`).
- The page's `pageLSN` is updated to `record.lsn`.
- The frame is marked dirty in the buffer pool.

---

## 5. Phase 3: Undo Pass & Compensation Log Records (CLRs)

The **Undo Pass** scans backward through the log to reverse the effects of all **loser transactions** (transactions that were active or aborted at crash time):

### Max-Heap Rollback
To ensure global serial order across multiple concurrent loser transactions, Undo maintains a max-heap `PriorityQueue<UndoEntry>` keyed by LSN:
```java
PriorityQueue<UndoEntry> queue = new PriorityQueue<>(Comparator.comparingLong(UndoEntry::lsn).reversed());
```
Each loser transaction's `lastLSN` is placed into the queue. The queue constantly polls the globally largest LSN, ensuring transactions are unwound in reverse chronological order.

### Compensation Log Records (CLRs)
When an operation is undone:
1. The before-image is restored on the page (or the record is deleted if it was an insert).
2. A **Compensation Log Record (CLR)** is appended to the WAL:
   - `tableName`, `pageId`, `slotIndex`
   - `restoredImage` (the restored before-image)
   - `undoNextLSN`: points to `update.prevLSN` (the next log record of this transaction that must be undone).
3. The page's `pageLSN` is set to the CLR's LSN.
4. If `undoNextLSN > 0`, `undoNextLSN` is pushed into the priority queue.
5. If `undoNextLSN == 0`, an `END` log record is appended for the transaction.

```
Log Record Chain during Undo:

[BEGIN] (LSN 100) ◄───┐
                      │ prevLSN
[UPDATE] (LSN 120) ◄──┴──┐
                         │ prevLSN
[UPDATE] (LSN 150) ◄─────┘
         │
         │ CRASH OCCURS HERE
         ▼
[CLR]    (LSN 200) ─── undoNextLSN = 120 ────────┐
         │                                       │
[CLR]    (LSN 210) ─── undoNextLSN = 100 ────┐   │
         │                                   │   │
[END]    (LSN 220)                           ▼   ▼
```

### Crash-During-Recovery Guarantee
If the database crashes while the Undo pass is running:
- On restart, the Analysis pass reads the CLRs written before the second crash.
- The CLR's `undoNextLSN` tells recovery where to resume undoing.
- The Undo pass **never undoes a CLR**; it simply follows the `undoNextLSN` pointer.
- This guarantees:
  1. No duplicate undos.
  2. Bounded recovery log size (recovery never enters an infinite loop).
  3. Total idempotency.

---

## 6. System Integration & Automatic Recovery

Crash recovery is seamlessly wired into the KoraDB storage lifecycle:

### Database Startup Hook (`DiskCatalog.useDatabase`)
When a client selects a database via `USE <dbname>`:
```java
Path walPath = StorageConfig.walFilePath(dbDir);
this.logManager = new LogManager(walPath);

// Pre-open TableFile handles
loadTablesMeta(dbDir).forEach(...);

// Run ARIES Crash Recovery
RecoveryManager recoveryManager = new RecoveryManager(walPath, logManager, openFiles::get, openFiles::values);
if (recoveryManager.needsRecovery()) {
    RecoveryResult result = recoveryManager.recover();
    if (result.hasRecoveredChanges()) {
        log.info("Recovered database '{}': {}", name, result);
    }
}

// Open and re-synchronize B+ Tree indexes
```

### Public API (`KoraDB.java`)
Programmatic crash recovery can be queried or invoked directly:
```java
KoraDB db = new KoraDB(Path.of("data"));
db.execute("USE production");

// Trigger ARIES recovery explicitly
RecoveryResult result = db.recover();
System.out.println("Redone: " + result.getRedoneCount() + ", Undone: " + result.getUndoneCount());
```

---

## 7. Verification & Test Coverage

The ARIES implementation is verified by `CrashRecoveryTest` and `LogRecordCodecTest`:

| Test Case | Scenario | Verification |
|---|---|---|
| `redoRecoversCommittedUpdates` | Crash before buffer pool flush with committed transactions | Redo pass reads WAL and restores missing committed rows into disk files. |
| `undoRollsBackUncommittedLoser` | Crash mid-transaction with active uncommitted mutations | Undo pass rolls back inserted/updated slots, writes CLRs, and removes uncommitted rows. |
| `mixedWinnersAndLosers` | Concurrent transactions: Txn 1 commits, Txn 2 active, Txn 3 commits | Winners are preserved, losers are rolled back; zero data corruption. |
| `clrIdempotencyDuringRepeatedRecovery` | Multiple consecutive crashes during recovery | CLRs are followed via `undoNextLSN`; no duplicate undo operations. |
| `recoveryWithCheckpoint` | Checkpoint taken before crash | Analysis and Redo start from checkpoint's `min(recLSN)`, minimizing recovery time. |
| `indexRebuiltAfterRecovery` | Table with B+ Tree secondary index | Index is re-synchronized with recovered table; point queries and range scans match recovered state. |
