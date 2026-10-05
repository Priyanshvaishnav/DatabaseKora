package dev.koradb.storage.recovery;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Output of the ARIES Analysis pass.
 */
public class AnalysisResult {
    private final long checkpointLsn;
    private final long redoStartLsn;
    private final Map<Long, TransactionRecoveryInfo> transactionTable;
    private final Map<PageKey, Long> dirtyPageTable;
    private final Set<Long> loserTxnIds;
    private final Set<Long> winnerTxnIds;

    public AnalysisResult(long checkpointLsn,
                          long redoStartLsn,
                          Map<Long, TransactionRecoveryInfo> transactionTable,
                          Map<PageKey, Long> dirtyPageTable,
                          Set<Long> loserTxnIds,
                          Set<Long> winnerTxnIds) {
        this.checkpointLsn = checkpointLsn;
        this.redoStartLsn = redoStartLsn;
        this.transactionTable = Collections.unmodifiableMap(transactionTable);
        this.dirtyPageTable = Collections.unmodifiableMap(dirtyPageTable);
        this.loserTxnIds = Collections.unmodifiableSet(loserTxnIds);
        this.winnerTxnIds = Collections.unmodifiableSet(winnerTxnIds);
    }

    public long getCheckpointLsn() {
        return checkpointLsn;
    }

    public long getRedoStartLsn() {
        return redoStartLsn;
    }

    public Map<Long, TransactionRecoveryInfo> getTransactionTable() {
        return transactionTable;
    }

    public Map<PageKey, Long> getDirtyPageTable() {
        return dirtyPageTable;
    }

    public Set<Long> getLoserTxnIds() {
        return loserTxnIds;
    }

    public Set<Long> getWinnerTxnIds() {
        return winnerTxnIds;
    }

    @Override
    public String toString() {
        return "AnalysisResult{cpLsn=" + checkpointLsn + ", redoStartLsn=" + redoStartLsn +
                ", losers=" + loserTxnIds + ", winners=" + winnerTxnIds +
                ", dirtyPages=" + dirtyPageTable.size() + "}";
    }
}
