package dev.koradb.transaction;

/**
 * Unique identifier for a database transaction.
 *
 * @param id monotonically increasing numeric identifier
 */
public record TransactionId(long id) implements Comparable<TransactionId> {

    public TransactionId {
        if (id < 0) {
            throw new IllegalArgumentException("TransactionId must be non-negative: " + id);
        }
    }

    @Override
    public int compareTo(TransactionId o) {
        return Long.compare(this.id, o.id);
    }

    public long value() {
        return id;
    }

    @Override
    public String toString() {
        return "Txn#" + id;
    }
}
