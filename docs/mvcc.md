# Multi-Version Concurrency Control (MVCC) — Design Document

## 1. What Problem Does It Solve?

In traditional concurrency control mechanisms based on **Strict Two-Phase Locking (2PL)**:
1. **Readers block Writers**: A transaction executing a long-running read query (e.g., an analytical scan or report generation) acquires shared locks on tables or pages, preventing concurrent transactions from updating or inserting data.
2. **Writers block Readers**: An active transaction modifying rows acquires exclusive locks, forcing incoming read operations to stall until the transaction commits or aborts.
3. **High Contention and Latency**: In read-heavy workloads with concurrent writes, lock queues lengthen, throughput collapses, and the probability of deadlocks increases dramatically.

### The MVCC Solution: "Readers Never Block Writers, Writers Never Block Readers"

**Multi-Version Concurrency Control (MVCC)** solves this fundamental limitation by maintaining multiple physical versions of each record. 

When a transaction updates or deletes a record:
- The old version is **not** overwritten in-place.
- Instead, the old version remains intact with a stamped end-of-visibility transaction ID (`xmax`).
- A new version is inserted with a stamped creation transaction ID (`xmin`).
- Read queries construct or reference a **point-in-time snapshot** (`ReadView`), selecting only the record versions that were committed prior to the snapshot's creation.

KoraDB implements MVCC across both in-memory tables and persistent disk-based slotted pages, supporting **Snapshot Isolation** and statement-level **Read Committed** isolation without acquiring table read locks.

---

## 2. Architecture & Data Structures

```
                                 [ Client Transaction ]
                                           │
                                           ▼
                                 Isolation Level Check
                     ┌─────────────────────┴─────────────────────┐
                     │                                           │
             [ SERIALIZABLE ]                          [ SNAPSHOT / READ_COMMITTED ]
                     │                                           │
            Strict 2PL Locks                            Lock-Free Reads
          (acquireShared/Exclusive)                  (MvccContext / ReadView)
                     │                                           │
                     └─────────────────────┬─────────────────────┘
                                           │
                                           ▼
                               [ Query Execution Engine ]
                                           │
                    ┌──────────────────────┴──────────────────────┐
                    │                                             │
          [ SeqScanOperator ]                           [ IndexScanOperator ]
                    │                                             │
                    └──────────────────────┬──────────────────────┘
                                           │
                                 Visibility Evaluation
                            (ReadView.isVisible(xmin, xmax))
                                           │
                     ┌─────────────────────┴─────────────────────┐
                     ▼                                           ▼
              [ Visible Version ]                         [ Invisible Version ]
              (Yielded to pipeline)                       (Skipped / Filtered)
```

### Tuple Header Structure

Every row in KoraDB (both in memory and serialized on disk) carries a **16-byte MVCC header**:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                            xmin                               |
|               (Transaction ID that created version)           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                            xmax                               |
|               (Transaction ID that deleted/expired version)   |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        User Record Data                       |
|               (Null bitmap, fixed & variable columns)        |
+---------------------------------------------------------------+
```

- **`xmin` (8 bytes)**: The transaction ID that inserted this version. Initialized to the inserting transaction's `txnId`.
- **`xmax` (8 bytes)**: The transaction ID that deleted this version (or replaced it during an update). Initialized to `0` (indicating the version is current and has not been deleted).

---

## 3. Snapshot Engine (`ReadView`)

A `ReadView` represents a consistent snapshot of database state at a specific instant in logical time:

```java
public class ReadView {
    private final long creatorTxnId;
    private final long lowWatermark;      // All txns < lowWatermark are committed
    private final long highWatermark;     // All txns >= highWatermark are unseen (in the future)
    private final Set<Long> activeTxnIds; // Txns active when snapshot was taken
    private final Predicate<Long> commitPredicate;
    private final Predicate<Long> abortPredicate;
}
```

### Visibility Rules

For a record version with creation ID `xmin` and deletion ID `xmax` to be visible to a snapshot with `ReadView`:

#### 1. Creation Visibility (`isCreatedVisible`)
1. **Self-Created**: If `xmin == creatorTxnId`, the version is **visible** (a transaction always sees its own uncommitted inserts).
2. **Prior to Low Watermark**: If `xmin < lowWatermark`, the transaction committed before snapshot creation unless recorded as aborted.
3. **Future Transactions**: If `xmin >= highWatermark`, the creating transaction began after snapshot creation $\rightarrow$ **invisible**.
4. **Concurrent Active Transactions**: If `xmin` was active when the snapshot was taken (`activeTxnIds.contains(xmin)`), it was not yet committed $\rightarrow$ **invisible**.
5. **Commit Status**: If `xmin` is marked committed in the commit predicate and not aborted $\rightarrow$ **visible**; otherwise **invisible**.

#### 2. Deletion Visibility (`isDeletedVisible`)
1. If `xmax == 0`, the record has not been deleted $\rightarrow$ not deleted.
2. If `xmax == creatorTxnId`, the current transaction deleted it $\rightarrow$ **deleted** (invisible).
3. If `xmax < lowWatermark` and committed $\rightarrow$ **deleted** (invisible).
4. If `xmax >= highWatermark`, deletion occurred in the future $\rightarrow$ **not deleted** to this snapshot.
5. If `activeTxnIds.contains(xmax)`, deleting transaction was in flight $\rightarrow$ **not deleted** to this snapshot.
6. If `commitPredicate.test(xmax)` and not aborted $\rightarrow$ **deleted** (invisible); otherwise **not deleted**.

#### Combined Visibility
$$\text{isVisible} = \text{isCreatedVisible}(xmin) \land \neg \text{isDeletedVisible}(xmax)$$

---

## 4. Isolation Levels: Snapshot Isolation vs. Read Committed

KoraDB supports three transaction isolation levels:

| Isolation Level | Snapshot Lifetime | Locking Strategy | Phantom / Non-Repeatable Reads |
|---|---|---|---|
| `SERIALIZABLE` | N/A | Strict 2PL (Shared / Exclusive table locks) | Prevented by 2PL locks |
| `SNAPSHOT` | Transaction duration (acquired on first read) | Lock-Free Reads; Row-level write-conflict detection | Prevented by consistent snapshot |
| `READ_COMMITTED` | Statement duration (refreshed per statement) | Lock-Free Reads; Row-level write-conflict detection | Statements see latest committed data |

### Snapshot Isolation
Under `IsolationLevel.SNAPSHOT`, a transaction obtains a `ReadView` upon its first read operation and retains that identical view throughout its lifetime. Consecutive queries within the same transaction observe the identical database state regardless of concurrent commits.

### Read Committed
Under `IsolationLevel.READ_COMMITTED`, `StatementExecutor` generates a fresh `ReadView` before evaluating each SQL statement. This allows long-running transactions to observe updates committed by other transactions as soon as those updates finish.

---

## 5. Write-Write Conflict Detection

Under Snapshot Isolation, when two concurrent transactions attempt to update or delete the same record version:
1. **First-Committer-Wins**: The first transaction to update or delete the record stamps its `txnId` into `xmax`.
2. **Conflict Detection**: If a concurrent transaction attempts to update or delete a record whose `xmax != 0` and `xmax` belongs to a concurrent or committed transaction, a `WriteConflictException` is raised:

```java
if (currentXmax != 0L && currentXmax != txn.getTxnId()) {
    if (txnManager.isCommitted(currentXmax) || txnManager.getActiveTxnIds().contains(currentXmax)) {
        throw new WriteConflictException("Write conflict on record: updated or deleted by concurrent txn " + currentXmax);
    }
}
```

3. **Automatic Abort**: `KoraDB` catches `WriteConflictException` at the top level and immediately aborts the conflicting transaction, preserving consistency.

---

## 6. Storage Layer Integration

### In-Memory Tables (`Table.java`)
In-memory tables maintain a list of all historical versions of each row. 
- When an `INSERT` occurs: row is stamped with `xmin = txn.getTxnId(), xmax = 0`.
- When an `UPDATE` occurs: old row's `xmax` is set to `txn.getTxnId()`, and the new row is added with `xmin = txn.getTxnId(), xmax = 0`.
- When a `DELETE` occurs: row's `xmax` is set to `txn.getTxnId()`.

### Disk Tables (`TableFile.java` & `RecordSerializer.java`)
Disk-based tables serialize the 16-byte version header into slotted page slots:
- **In-Place Stamp**: `TableFile.deleteRow(rid, txn)` mutates only the 8-byte `xmax` at record offset 8 within the slotted page buffer frame. The record is **not** immediately removed, allowing active snapshot readers to continue reading the slot.
- **Rollback Undo**: If a transaction aborts, undo actions (`UpdateUndoAction`, `DeleteUndoAction`) reset `xmax` back to `0L`, restoring visibility to future readers without physical row movement.

---

## 7. Vacuum Garbage Collection (`VacuumManager`)

Over time, updates and deletions accumulate "dead" record versions that are no longer visible to any active or future transaction.

### Safety Condition for Reclamation
A record version is eligible for physical deletion if:
$$xmax \neq 0 \land xmax < \text{minActiveTxnId} \land \text{isCommitted}(xmax)$$

Where $\text{minActiveTxnId} = \min(\text{active transactions}, \text{nextTxnId})$.

```
  xmin=1, xmax=2       xmin=2, xmax=0
 ┌──────────────┐     ┌──────────────┐
 │ Old Version  │ ──► │ Current Row  │
 └──────────────┘     └──────────────┘
        │
   xmax=2 < minActiveTxnId (e.g. 5)
        │
        ▼
   [ VACUUM ]
 Physical Reclamation
 (Slot freed in Page)
```

`VacuumManager` runs on demand or as a background service:
```java
VacuumManager vacuumMgr = db.createVacuumManager();
long purgedCount = vacuumMgr.vacuumDatabase();
```

---

## 8. Crash Recovery & Transaction ID Continuity

When restarting the database after a shutdown or crash:
1. `DiskCatalog` scans all persistent table files and the Write-Ahead Log to determine the maximum recorded transaction ID (`maxTxnId`).
2. `TransactionManager` is initialized with `initNextTxnId(maxTxnId + 100)`.
3. All historical transactions with `id < initialTxnId` are automatically treated as committed unless flagged as aborted during ARIES recovery analysis.
4. This ensures existing records on disk remain visible to newly created transactions after restart without requiring rewrites of existing page headers.
