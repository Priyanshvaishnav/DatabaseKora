package dev.koradb.transaction.lock;

import dev.koradb.transaction.TransactionId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Deadlock Detection and Resolution")
class DeadlockTest {

    @Test
    @DisplayName("Circular wait between two transactions is broken via deadlock timeout")
    void testCircularWaitDeadlockResolution() throws Exception {
        LockManager lockManager = new LockManager(300); // 300ms timeout
        TransactionId txn1 = new TransactionId(101);
        TransactionId txn2 = new TransactionId(102);

        LockResource resA = LockResource.table("resourceA");
        LockResource resB = LockResource.table("resourceB");

        CountDownLatch step1 = new CountDownLatch(2);
        AtomicBoolean txn1Deadlocked = new AtomicBoolean(false);
        AtomicBoolean txn2Deadlocked = new AtomicBoolean(false);
        AtomicBoolean txn1Completed = new AtomicBoolean(false);
        AtomicBoolean txn2Completed = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: Locks A, then attempts to lock B
        Future<?> f1 = executor.submit(() -> {
            try {
                lockManager.acquireExclusive(txn1, resA);
                step1.countDown();
                step1.await(); // ensure both have locked their first resource

                // Short sleep so both threads hit their second request concurrently
                Thread.sleep(50);
                lockManager.acquireExclusive(txn1, resB);
                txn1Completed.set(true);
            } catch (DeadlockException e) {
                txn1Deadlocked.set(true);
                lockManager.releaseAll(txn1); // Abort and release
            } catch (Exception e) {
                // unexpected
            }
        });

        // Thread 2: Locks B, then attempts to lock A
        Future<?> f2 = executor.submit(() -> {
            try {
                lockManager.acquireExclusive(txn2, resB);
                step1.countDown();
                step1.await(); // ensure both have locked their first resource

                Thread.sleep(50);
                lockManager.acquireExclusive(txn2, resA);
                txn2Completed.set(true);
            } catch (DeadlockException e) {
                txn2Deadlocked.set(true);
                lockManager.releaseAll(txn2); // Abort and release
            } catch (Exception e) {
                // unexpected
            }
        });

        f1.get(3, TimeUnit.SECONDS);
        f2.get(3, TimeUnit.SECONDS);
        executor.shutdown();

        // At least one of the two transactions must detect deadlock / timeout and abort
        assertThat(txn1Deadlocked.get() || txn2Deadlocked.get()).isTrue();

        // Clean up remaining locks
        lockManager.releaseAll(txn1);
        lockManager.releaseAll(txn2);
    }
}
