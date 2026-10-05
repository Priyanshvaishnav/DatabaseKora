package dev.koradb.replication.master;

import dev.koradb.replication.model.ReplicationMode;
import dev.koradb.replication.model.ReplicationTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates synchronous commits across active synchronous replicas.
 *
 * <p>When a transaction commits in {@link ReplicationMode#SYNC}, the committing thread
 * calls {@link #awaitCommit(long, long)} which suspends until at least one synchronous replica
 * reports having flushed the commit LSN to disk.
 */
public final class SyncReplicationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SyncReplicationCoordinator.class);

    private final ConcurrentNavigableMap<Long, CompletableFuture<Void>> pendingSyncCommits =
            new ConcurrentSkipListMap<>();
    private final AtomicLong highestSyncFlushedLsn = new AtomicLong(0L);

    /**
     * Registers a required commit LSN and returns a future completing when a synchronous replica flushes it.
     */
    public CompletableFuture<Void> registerCommit(long commitLsn) {
        if (highestSyncFlushedLsn.get() >= commitLsn) {
            return CompletableFuture.completedFuture(null);
        }

        return pendingSyncCommits.computeIfAbsent(commitLsn, k -> {
            CompletableFuture<Void> future = new CompletableFuture<>();
            if (highestSyncFlushedLsn.get() >= commitLsn) {
                future.complete(null);
            }
            return future;
        });
    }

    /**
     * Called when a replica acknowledges flushing up to {@code flushedLsn}.
     */
    public void onReplicaFlushed(String replicaId, ReplicationMode mode, long flushedLsn) {
        if (mode != ReplicationMode.SYNC && mode != ReplicationMode.SEMI_SYNC) {
            return;
        }

        highestSyncFlushedLsn.accumulateAndGet(flushedLsn, Math::max);

        // Complete all pending commits at or before this flushed LSN
        Map<Long, CompletableFuture<Void>> satisfied = pendingSyncCommits.headMap(flushedLsn, true);
        if (!satisfied.isEmpty()) {
            for (Map.Entry<Long, CompletableFuture<Void>> entry : satisfied.entrySet()) {
                entry.getValue().complete(null);
                pendingSyncCommits.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Suspends the calling thread until a synchronous replica acknowledges the commit LSN.
     *
     * @param commitLsn the target LSN that must be flushed by a replica
     * @param timeoutMs maximum time to wait before timing out
     * @throws ReplicationTimeoutException if timeout elapses
     */
    public void awaitCommit(long commitLsn, long timeoutMs) {
        CompletableFuture<Void> future = registerCommit(commitLsn);
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pendingSyncCommits.remove(commitLsn, future);
            throw new ReplicationTimeoutException(
                    "Timed out after %d ms waiting for synchronous replica ACK of commit LSN %d"
                            .formatted(timeoutMs, commitLsn), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendingSyncCommits.remove(commitLsn, future);
            throw new RuntimeException("Interrupted while waiting for synchronous replication ACK", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Synchronous replication failed", e.getCause());
        }
    }

    /**
     * Cancels all pending commit futures, typically during shutdown.
     */
    public void close() {
        for (CompletableFuture<Void> f : pendingSyncCommits.values()) {
            f.cancel(false);
        }
        pendingSyncCommits.clear();
    }
}
