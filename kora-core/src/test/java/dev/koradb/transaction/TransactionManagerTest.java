package dev.koradb.transaction;

import dev.koradb.catalog.*;
import dev.koradb.mvcc.ReadView;
import dev.koradb.sql.ast.ColumnDef;
import dev.koradb.sql.ast.DataType;
import dev.koradb.transaction.lock.LockManager;
import dev.koradb.transaction.lock.LockResource;
import dev.koradb.transaction.undo.DeleteUndoAction;
import dev.koradb.transaction.undo.InsertUndoAction;
import dev.koradb.transaction.undo.UpdateUndoAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Transaction Manager & Undo Engine")
class TransactionManagerTest {

    private InMemoryCatalog catalog;
    private LockManager lockManager;
    private TransactionManager transactionManager;
    private Table usersTable;

    @BeforeEach
    void setUp() {
        catalog = new InMemoryCatalog();
        catalog.createDatabase("testdb");
        catalog.useDatabase("testdb");
        lockManager = new LockManager();
        transactionManager = new TransactionManager(lockManager, catalog);

        Schema schema = new Schema(List.of(
                new ColumnDef("id", DataType.INT),
                new ColumnDef("name", DataType.VARCHAR),
                new ColumnDef("score", DataType.DOUBLE)
        ));
        usersTable = new Table("users", schema);
        catalog.createTable(usersTable);
    }

    @Test
    @DisplayName("Transaction lifecycle: begin, commit, lock release")
    void testCommitLifecycle() {
        Transaction txn = transactionManager.beginTransaction();
        assertThat(txn.getState()).isEqualTo(TransactionState.ACTIVE);
        assertThat(txn.isActive()).isTrue();

        lockManager.acquireExclusive(txn.getId(), LockResource.table("users"));
        assertThat(lockManager.isLocked(LockResource.table("users"))).isTrue();

        transactionManager.commitTransaction(txn);
        assertThat(txn.getState()).isEqualTo(TransactionState.COMMITTED);
        assertThat(txn.isActive()).isFalse();

        // SS2PL locks released upon commit
        assertThat(lockManager.isLocked(LockResource.table("users"))).isFalse();

        // Cannot commit again
        assertThatThrownBy(() -> transactionManager.commitTransaction(txn))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Transaction lifecycle: begin, abort, lock release")
    void testAbortLifecycle() {
        Transaction txn = transactionManager.beginTransaction();
        lockManager.acquireExclusive(txn.getId(), LockResource.table("users"));

        transactionManager.abortTransaction(txn);
        assertThat(txn.getState()).isEqualTo(TransactionState.ABORTED);
        assertThat(txn.isActive()).isFalse();

        // SS2PL locks released upon abort
        assertThat(lockManager.isLocked(LockResource.table("users"))).isFalse();

        // Aborting an already aborted transaction is idempotent
        transactionManager.abortTransaction(txn);
        assertThat(txn.getState()).isEqualTo(TransactionState.ABORTED);
    }

    /** Takes a ReadView the moment a txn's locks are released, as a reader woken by them would. */
    private static final class SnapshotOnReleaseLockManager extends LockManager {
        TransactionManager txnManager;
        ReadView viewAtRelease;
        boolean activeAtRelease;

        @Override
        public void releaseAll(TransactionId txnId) {
            activeAtRelease = txnManager.isActive(txnId.value());
            viewAtRelease = txnManager.createReadView(null);
            super.releaseAll(txnId);
        }
    }

    @Test
    @DisplayName("A reader woken by a commit's lock release sees the committed rows")
    void testCommitLeavesActiveSetBeforeReleasingLocks() {
        SnapshotOnReleaseLockManager locks = new SnapshotOnReleaseLockManager();
        TransactionManager tm = new TransactionManager(locks, catalog);
        locks.txnManager = tm;

        Transaction writer = tm.beginTransaction();
        locks.acquireExclusive(writer.getId(), LockResource.table("users"));
        tm.commitTransaction(writer);

        assertThat(locks.activeAtRelease).isFalse();
        assertThat(locks.viewAtRelease.getActiveTxnIds()).doesNotContain(writer.getId().value());
        assertThat(locks.viewAtRelease.isVisible(writer.getId().value(), 0L)).isTrue();
    }

    @Test
    @DisplayName("A reader woken by an abort's lock release does not see the txn as in flight")
    void testAbortLeavesActiveSetBeforeReleasingLocks() {
        SnapshotOnReleaseLockManager locks = new SnapshotOnReleaseLockManager();
        TransactionManager tm = new TransactionManager(locks, catalog);
        locks.txnManager = tm;

        Transaction writer = tm.beginTransaction();
        locks.acquireExclusive(writer.getId(), LockResource.table("users"));
        tm.abortTransaction(writer);

        assertThat(locks.activeAtRelease).isFalse();
        assertThat(locks.viewAtRelease.getActiveTxnIds()).doesNotContain(writer.getId().value());
        assertThat(locks.viewAtRelease.isVisible(writer.getId().value(), 0L)).isFalse();
    }

    @Test
    @DisplayName("Rollback applies InsertUndoAction to remove inserted row")
    void testInsertUndoAction() {
        Row row = new Row(Map.of("id", 1, "name", "Alice", "score", 95.0));
        usersTable.insertRow(row);
        assertThat(usersTable.rowCount()).isEqualTo(1);

        Transaction txn = transactionManager.beginTransaction();
        txn.addUndoAction(new InsertUndoAction("users", row));

        transactionManager.abortTransaction(txn);
        assertThat(usersTable.rowCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Rollback applies DeleteUndoAction to restore deleted row")
    void testDeleteUndoAction() {
        Row row = new Row(Map.of("id", 1, "name", "Bob", "score", 88.5));
        usersTable.insertRow(row);
        usersTable.deleteRow(0);
        assertThat(usersTable.rowCount()).isEqualTo(0);

        Transaction txn = transactionManager.beginTransaction();
        txn.addUndoAction(new DeleteUndoAction("users", row));

        transactionManager.abortTransaction(txn);
        assertThat(usersTable.rowCount()).isEqualTo(1);
        assertThat(usersTable.getRows().get(0).get("name")).isEqualTo("Bob");
    }

    @Test
    @DisplayName("Rollback applies UpdateUndoAction to restore pre-update values")
    void testUpdateUndoAction() {
        Row row = new Row(Map.of("id", 1, "name", "Charlie", "score", 70.0));
        usersTable.insertRow(row);

        Row oldSnapshot = row.copy();
        row.set("score", 99.0);
        row.set("name", "Charles");

        Transaction txn = transactionManager.beginTransaction();
        txn.addUndoAction(new UpdateUndoAction("users", oldSnapshot, row));

        transactionManager.abortTransaction(txn);
        assertThat(row.get("score")).isEqualTo(70.0);
        assertThat(row.get("name")).isEqualTo("Charlie");
    }

    @Test
    @DisplayName("LIFO undo order reverses multi-statement mutations correctly")
    void testLifoMultiStatementRollback() {
        Row r1 = new Row(Map.of("id", 1, "name", "Alpha", "score", 10.0));
        Row r2 = new Row(Map.of("id", 2, "name", "Beta", "score", 20.0));
        usersTable.insertRow(r1);

        Transaction txn = transactionManager.beginTransaction();

        // Operation 1: Insert r2
        usersTable.insertRow(r2);
        txn.addUndoAction(new InsertUndoAction("users", r2));

        // Operation 2: Update r1
        Row r1Old = r1.copy();
        r1.set("score", 50.0);
        txn.addUndoAction(new UpdateUndoAction("users", r1Old, r1));

        // Operation 3: Delete r1
        usersTable.deleteRow(0); // removes r1
        txn.addUndoAction(new DeleteUndoAction("users", r1));

        // At this point, only r2 is in the table, and r1 has score 50.0
        assertThat(usersTable.rowCount()).isEqualTo(1);

        // Abort:
        // 1. Undo Delete r1 -> r1 restored to table
        // 2. Undo Update r1 -> r1 score restored to 10.0
        // 3. Undo Insert r2 -> r2 removed from table
        transactionManager.abortTransaction(txn);

        assertThat(usersTable.rowCount()).isEqualTo(1);
        Row remaining = usersTable.getRows().get(0);
        assertThat(remaining.get("id")).isEqualTo(1);
        assertThat(remaining.get("name")).isEqualTo("Alpha");
        assertThat(remaining.get("score")).isEqualTo(10.0);
    }
}
