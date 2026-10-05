package dev.koradb.storage.recovery;

import java.util.Objects;

/**
 * Result of executing the ARIES crash recovery algorithm.
 */
public class RecoveryResult {

    private final AnalysisResult analysisResult;
    private final int redoneCount;
    private final int undoneCount;
    private final long durationMs;

    public RecoveryResult(AnalysisResult analysisResult, int redoneCount, int undoneCount, long durationMs) {
        this.analysisResult = Objects.requireNonNull(analysisResult, "analysisResult must not be null");
        this.redoneCount = redoneCount;
        this.undoneCount = undoneCount;
        this.durationMs = durationMs;
    }

    public AnalysisResult getAnalysisResult() {
        return analysisResult;
    }

    public int getRedoneCount() {
        return redoneCount;
    }

    public int getUndoneCount() {
        return undoneCount;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public boolean hasRecoveredChanges() {
        return redoneCount > 0 || undoneCount > 0;
    }

    @Override
    public String toString() {
        return "RecoveryResult{" +
                "redoneCount=" + redoneCount +
                ", undoneCount=" + undoneCount +
                ", durationMs=" + durationMs +
                ", analysis=" + analysisResult +
                '}';
    }
}
