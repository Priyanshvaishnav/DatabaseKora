# ACID Transactions, Concurrency Control & Strict Two-Phase Locking — Design Document

## 1. What Problem Does It Solve?

Prior to **Milestone 7**, KoraDB executed all SQL statements immediately and in isolation from any transaction context:
1. **No Atomicity**: If a multi-statement workflow failed midway (e.g. debiting an account succeeded, but crediting another failed), previously applied mutations could not be rolled back.
2. **No Isolation & Concurrency Hazards**: Concurrent operations on the same table or records would cause dirty reads, non-repeatable reads, lost updates, and write skews.
3. **Cascading Aborts**: Without Strict 2PL discipline, if transaction $T_1$ read uncommitted data written by $T_2$, and $T_2$ subsequently aborted, $T_1$ would have to be forcibly aborted as well.
4. **No Session Context**: Each statement had no concept of an active transaction or client session.

**Milestone 7** implements full **ACID Transactions**, **Concurrency Control via Strict Two-Phase Locking (SS2PL)**, and **In-Flight Rollback via a LIFO Undo Engine**.

---

## 2. Architecture & Pipeline

```
                     Client Connection / REPL / API
                                   │
                         ┌─────────▼─────────┐
                         │      Session      │ (Holds active DB & Transaction)
                         └─────────┬─────────┘
                                   │
                         ┌─────────▼─────────┐
                         │    KoraDB Core    │
                         └────┬──────────┬───┘
                              │          │
        ┌─────────────────────▼───┐  ┌───▼─────────────────────┐
        │   TransactionManager    │  │       LockManager       │
        │ - beginTransaction()    │  │ - acquireShared()       │
        │ - commitTransaction()   │  │ - acquireExclusive()    │
        │ - abortTransaction()    │  │ - releaseAll(txnId)     │
        └─────────────┬───────────┘  └─────────────────────────┘
                      │
            ┌─────────▼─────────┐
            │   Undo Stack      │
            │   (LIFO Buffer)   │
            │ - InsertUndoAction│
            │ - UpdateUndoAction│
            │ - DeleteUndoAction│
            └───────────────────┘
```

---

## 3. Strict Two-Phase Locking (SS2PL)

To achieve **Serializability** and prevent cascading aborts, KoraDB enforces **Strict Two-Phase Locking (SS2PL)**:

### 3.1 Lock Modes & Compatibility Matrix

| Requested Mode | Currently Held: SHARED | Currently Held: EXCLUSIVE |
| :--- | :--- | :--- |
| **SHARED (S)** | Compatible (Granted) | Conflict (Wait) |
| **EXCLUSIVE (X)** | Conflict (Wait) | Conflict (Wait) |

- **SHARED (Read Lock)**: Acquired by `SELECT` and `EXPLAIN` queries. Multiple transactions can hold SHARED locks concurrently on the same resource.
- **EXCLUSIVE (Write Lock)**: Acquired by `INSERT`, `UPDATE`, `DELETE`, and DDL statements. Requires complete mutual exclusion against all other transactions.

### 3.2 Lock Upgrades
If a transaction currently holds a `SHARED` lock on a resource and subsequently issues a write operation requiring an `EXCLUSIVE` lock:
- If the requesting transaction is the **sole holder** of the shared lock, the lock is upgraded to `EXCLUSIVE` immediately.
- If other transactions also hold `SHARED` locks on the resource, the requesting transaction waits until all other holders release their locks or until a timeout occurs.

### 3.3 Strict 2PL Discipline
Unlike generic 2PL (which allows shrinking before commit), **Strict 2PL** requires that **all acquired locks are held until the transaction terminates**.
Upon `COMMIT` or `ROLLBACK`, `LockManager.releaseAll(TransactionId txnId)` atomically releases all held locks across all resources and notifies waiting threads.

---

## 4. In-Flight Rollback & LIFO Undo Engine

When a transaction modifies table rows, it records fine-grained compensatory actions onto its in-memory **Undo Stack**:

- [`InsertUndoAction`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/transaction/undo/InsertUndoAction.java):
  - On rollback, removes the inserted row from the table.
  - For disk-backed tables (`DiskTable`), deletes the allocated slot in `TableFile` and deletes the corresponding key from all associated B+ Tree indexes.
- [`UpdateUndoAction`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/transaction/undo/UpdateUndoAction.java):
  - Retains a snapshot of the row prior to the update.
  - On rollback, restores the original column values and synchronizes index entries.
- [`DeleteUndoAction`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/transaction/undo/DeleteUndoAction.java):
  - Retains the deleted row.
  - On rollback, re-inserts the row into the table and updates all indexes.

During `abortTransaction()`, the transaction pops each `UndoAction` in reverse chronological order (**LIFO**), returning the database to its exact pre-transaction state.

---

## 5. Session Context & Autocommit

KoraDB supports two execution modes:

### 5.1 Explicit Transactions
A client begins an explicit transaction by executing:
```sql
BEGIN [TRANSACTION];
INSERT INTO accounts VALUES (1, 'Alice', 1000.0);
UPDATE accounts SET balance = 500.0 WHERE id = 1;
COMMIT [TRANSACTION];   -- or ROLLBACK [TRANSACTION];
```
- Statements executed within the transaction hold all locks until explicit termination.
- Uncommitted modifications are private to the transaction under SS2PL.

### 5.2 Autocommit Mode
When no explicit transaction is active on a `Session`, every standalone SQL statement executes in an isolated single-statement transaction:
- An ephemeral `Transaction` is started.
- The statement executes under SS2PL locks and records undo actions.
- If the statement succeeds, the transaction commits automatically.
- If any exception occurs (syntax error, schema violation, or deadlock), the transaction aborts and rolls back automatically.

---

## 6. Deadlock Detection & Resolution

Deadlocks occur when two or more transactions form a circular dependency waiting for locks held by each other:
$$T_1 \xrightarrow{\text{waits for}} T_2 \xrightarrow{\text{waits for}} T_1$$

KoraDB employs **timeout-based deadlock detection**:
- Every lock acquisition enforces a configurable timeout (default 2000ms, configurable via `LockManager.setDefaultTimeoutMs(long ms)`).
- When a lock wait exceeds the deadline, [`DeadlockException`](file:///D:/Projects/DB/kora-db/kora-core/src/main/java/dev/koradb/transaction/lock/DeadlockException.java) is thrown.
- The transaction manager catches the exception, aborts the victim transaction, applies all undo actions, and releases all its locks, breaking the circular wait and allowing other transactions to complete.

---

## 7. Key Classes & Package Structure

```
dev.koradb/
├── session/
│   └── Session.java                     # Client session state & active transaction
├── transaction/
│   ├── TransactionId.java               # Monotonic transaction ID record
│   ├── TransactionState.java            # ACTIVE, COMMITTED, ABORTED
│   ├── IsolationLevel.java              # SERIALIZABLE, REPEATABLE_READ, READ_COMMITTED
│   ├── Transaction.java                 # Holds txn state, LIFO undo stack
│   ├── TransactionManager.java          # Lifecycle orchestrator (begin, commit, abort)
│   ├── lock/
│   │   ├── LockMode.java                # SHARED, EXCLUSIVE
│   │   ├── LockResource.java            # Table and record lock identifiers
│   │   ├── LockManager.java             # SS2PL lock manager, upgrade logic, timeouts
│   │   └── DeadlockException.java       # Deadlock / timeout exception
│   └── undo/
│       ├── UndoAction.java              # Functional undo interface
│       ├── InsertUndoAction.java        # Reverses INSERT
│       ├── UpdateUndoAction.java        # Reverses UPDATE
│       └── DeleteUndoAction.java        # Reverses DELETE
```
