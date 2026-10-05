package dev.koradb.transaction.lock;

import dev.koradb.transaction.TransactionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Strict 2PL Lock Manager")
class LockManagerTest {

    private LockManager lockManager;
    private final TransactionId txn1 = new TransactionId(1);
    private final TransactionId txn2 = new TransactionId(2);
    private final TransactionId txn3 = new TransactionId(3);
    private final LockResource resUsers = LockResource.table("users");
    private final LockResource resOrders = LockResource.table("orders");

    @BeforeEach
    void setUp() {
        lockManager = new LockManager(200); // 200ms timeout for tests
    }

    @Test
    @DisplayName("Multiple transactions can concurrently acquire SHARED locks on same resource")
    void testConcurrentSharedLocks() {
        lockManager.acquireShared(txn1, resUsers);
        lockManager.acquireShared(txn2, resUsers);
        lockManager.acquireShared(txn3, resUsers);

        assertThat(lockManager.isLocked(resUsers)).isTrue();
        assertThat(lockManager.getLockMode(txn1, resUsers)).contains(LockMode.SHARED);
        assertThat(lockManager.getLockMode(txn2, resUsers)).contains(LockMode.SHARED);
        assertThat(lockManager.getLockMode(txn3, resUsers)).contains(LockMode.SHARED);
    }

    @Test
    @DisplayName("Exclusive lock provides mutual exclusion against shared locks")
    void testExclusiveLockBlocksShared() {
        lockManager.acquireExclusive(txn1, resUsers);
        assertThat(lockManager.getLockMode(txn1, resUsers)).contains(LockMode.EXCLUSIVE);

        // txn2 requesting SHARED should time out and throw DeadlockException
        assertThatThrownBy(() -> lockManager.acquireShared(txn2, resUsers))
                .isInstanceOf(DeadlockException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("Shared lock blocks exclusive lock requests from other transactions")
    void testSharedLockBlocksExclusive() {
        lockManager.acquireShared(txn1, resUsers);

        // txn2 requesting EXCLUSIVE should time out
        assertThatThrownBy(() -> lockManager.acquireExclusive(txn2, resUsers))
                .isInstanceOf(DeadlockException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("Exclusive lock blocks exclusive lock requests from other transactions")
    void testExclusiveBlocksExclusive() {
        lockManager.acquireExclusive(txn1, resUsers);

        assertThatThrownBy(() -> lockManager.acquireExclusive(txn2, resUsers))
                .isInstanceOf(DeadlockException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("Single transaction can upgrade SHARED lock to EXCLUSIVE lock immediately")
    void testLockUpgradeSingleHolder() {
        lockManager.acquireShared(txn1, resUsers);
        assertThat(lockManager.getLockMode(txn1, resUsers)).contains(LockMode.SHARED);

        // Upgrade
        lockManager.acquireExclusive(txn1, resUsers);
        assertThat(lockManager.getLockMode(txn1, resUsers)).contains(LockMode.EXCLUSIVE);
    }

    @Test
    @DisplayName("Lock upgrade blocked when multiple transactions hold SHARED lock")
    void testLockUpgradeMultipleHoldersBlocked() {
        lockManager.acquireShared(txn1, resUsers);
        lockManager.acquireShared(txn2, resUsers);

        // txn1 cannot upgrade because txn2 also holds SHARED
        assertThatThrownBy(() -> lockManager.acquireExclusive(txn1, resUsers))
                .isInstanceOf(DeadlockException.class)
                .hasMessageContaining("timeout upgrading");
    }

    @Test
    @DisplayName("releaseAll atomically releases all locks held by transaction across all resources")
    void testReleaseAll() throws InterruptedException {
        lockManager.acquireExclusive(txn1, resUsers);
        lockManager.acquireExclusive(txn1, resOrders);

        assertThat(lockManager.getLockCount(txn1)).isEqualTo(2);

        CountDownLatch acquired = new CountDownLatch(1);
        AtomicBoolean txn2Acquired = new AtomicBoolean(false);

        Thread thread = new Thread(() -> {
            // Should succeed once txn1 releases
            lockManager.acquireExclusive(txn2, resUsers, 1000);
            txn2Acquired.set(true);
            acquired.countDown();
        });
        thread.start();

        // Release all locks of txn1
        Thread.sleep(50);
        lockManager.releaseAll(txn1);

        boolean done = acquired.await(1, TimeUnit.SECONDS);
        thread.join();

        assertThat(done).isTrue();
        assertThat(txn2Acquired.get()).isTrue();
        assertThat(lockManager.getLockCount(txn1)).isEqualTo(0);
        assertThat(lockManager.getLockMode(txn2, resUsers)).contains(LockMode.EXCLUSIVE);
    }

    @Test
    @DisplayName("Lock acquisition is idempotent for same transaction and mode")
    void testIdempotentAcquisition() {
        lockManager.acquireShared(txn1, resUsers);
        lockManager.acquireShared(txn1, resUsers); // No-op
        assertThat(lockManager.getLockMode(txn1, resUsers)).contains(LockMode.SHARED);

        lockManager.acquireExclusive(txn2, resOrders);
        lockManager.acquireExclusive(txn2, resOrders); // No-op
        lockManager.acquireShared(txn2, resOrders);    // EXCLUSIVE implies SHARED
        assertThat(lockManager.getLockMode(txn2, resOrders)).contains(LockMode.EXCLUSIVE);
    }
}
