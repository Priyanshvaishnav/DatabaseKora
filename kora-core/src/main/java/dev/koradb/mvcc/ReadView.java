package dev.koradb.mvcc;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A consistent point-in-time database snapshot for Multi-Version Concurrency Control (MVCC).
 *
 * <p>Captures active transaction state at the moment the snapshot is taken:
 * <ul>
 *   <li>{@code creatorTxnId}: transaction ID of the reader (0 if none / standalone).</li>
 *   <li>{@code lowWatermark}: minimum active transaction ID (all {@code txn < lowWatermark} are completed).</li>
 *   <li>{@code highWatermark}: transaction counter at snapshot creation (all {@code txn >= highWatermark} are invisible).</li>
 *   <li>{@code activeTxnIds}: set of transactions in-flight when the snapshot was established.</li>
 * </ul>
 */
public final class ReadView {

    private final long creatorTxnId;
    private final long lowWatermark;
    private final long highWatermark;
    private final Set<Long> activeTxnIds;
    private final Predicate<Long> isCommittedPredicate;
    private final Predicate<Long> isAbortedPredicate;

    public ReadView(long creatorTxnId,
                    long lowWatermark,
                    long highWatermark,
                    Set<Long> activeTxnIds,
                    Predicate<Long> isCommittedPredicate,
                    Predicate<Long> isAbortedPredicate) {
        this.creatorTxnId = creatorTxnId;
        this.lowWatermark = lowWatermark;
        this.highWatermark = highWatermark;
        this.activeTxnIds = Collections.unmodifiableSet(Objects.requireNonNull(activeTxnIds, "activeTxnIds must not be null"));
        this.isCommittedPredicate = Objects.requireNonNull(isCommittedPredicate, "isCommittedPredicate must not be null");
        this.isAbortedPredicate = Objects.requireNonNull(isAbortedPredicate, "isAbortedPredicate must not be null");
    }

    /**
     * Creates a ReadView where all committed rows are visible (default for non-transactional reads).
     */
    public static ReadView allVisible() {
        return new ReadView(0L, Long.MAX_VALUE, Long.MAX_VALUE, Collections.emptySet(), txn -> true, txn -> false);
    }

    public long getCreatorTxnId() {
        return creatorTxnId;
    }

    public long getLowWatermark() {
        return lowWatermark;
    }

    public long getHighWatermark() {
        return highWatermark;
    }

    public Set<Long> getActiveTxnIds() {
        return activeTxnIds;
    }

    /**
     * Determines whether a tuple version with the given {@code xmin} and {@code xmax}
     * is visible to this snapshot.
     *
     * @param xmin transaction ID that created/inserted this row version
     * @param xmax transaction ID that deleted/updated this row version (0 if alive)
     * @return {@code true} if the row version is visible; {@code false} otherwise
     */
    public boolean isVisible(long xmin, long xmax) {
        // -------------------------------------------------------------
        // Step 1: Check creation visibility (xmin)
        // -------------------------------------------------------------
        if (xmin != 0L) {
            if (creatorTxnId != 0L && xmin == creatorTxnId) {
                // Created by our own transaction: visible unless we also deleted it
                if (xmax == creatorTxnId) {
                    return false;
                }
                return true;
            }

            // Created after this snapshot was taken
            if (xmin >= highWatermark) {
                return false;
            }

            // In-flight when this snapshot was taken
            if (activeTxnIds.contains(xmin)) {
                return false;
            }

            // Explicitly aborted
            if (isAbortedPredicate.test(xmin)) {
                return false;
            }

            // If xmin < lowWatermark or committed, it is visible
            if (xmin >= lowWatermark && !isCommittedPredicate.test(xmin)) {
                return false;
            }
        }

        // -------------------------------------------------------------
        // Step 2: Check deletion visibility (xmax)
        // -------------------------------------------------------------
        if (xmax == 0L) {
            // Not deleted
            return true;
        }

        if (creatorTxnId != 0L && xmax == creatorTxnId) {
            // Deleted by our own transaction
            return false;
        }

        // Deleted after this snapshot was taken -> deletion is invisible -> row is visible
        if (xmax >= highWatermark) {
            return true;
        }

        // Deletion was in-flight when this snapshot was taken -> deletion is invisible -> row is visible
        if (activeTxnIds.contains(xmax)) {
            return true;
        }

        // Deleter transaction was aborted -> deletion rolled back -> row is visible
        if (isAbortedPredicate.test(xmax)) {
            return true;
        }

        // Deleter transaction was committed before this snapshot -> deletion is visible -> row is dead
        if (xmax < lowWatermark || isCommittedPredicate.test(xmax)) {
            return false;
        }

        return true;
    }

    @Override
    public String toString() {
        return "ReadView{" +
                "creatorTxn=" + creatorTxnId +
                ", lowWatermark=" + lowWatermark +
                ", highWatermark=" + highWatermark +
                ", activeTxns=" + activeTxnIds +
                '}';
    }
}
