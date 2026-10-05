package dev.koradb.storage.recovery;

/**
 * State of a transaction tracked during recovery passes.
 */
public class TransactionRecoveryInfo {
    private final long txnId;
    private TransactionStatus status;
    private long lastLsn;
    private long undoNextLsn;

    public TransactionRecoveryInfo(long txnId, TransactionStatus status, long lastLsn) {
        this.txnId = txnId;
        this.status = status;
        this.lastLsn = lastLsn;
        this.undoNextLsn = lastLsn;
    }

    public long getTxnId() {
        return txnId;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public long getLastLsn() {
        return lastLsn;
    }

    public void setLastLsn(long lastLsn) {
        this.lastLsn = lastLsn;
    }

    public long getUndoNextLsn() {
        return undoNextLsn;
    }

    public void setUndoNextLsn(long undoNextLsn) {
        this.undoNextLsn = undoNextLsn;
    }

    @Override
    public String toString() {
        return "TxnRecoveryInfo{id=" + txnId + ", status=" + status + ", lastLsn=" + lastLsn + "}";
    }
}
