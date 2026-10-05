package dev.koradb.observability.collectors;

import dev.koradb.observability.metrics.Counter;
import dev.koradb.observability.metrics.MetricsRegistry;
import dev.koradb.transaction.TransactionManager;

import java.util.Objects;

/**
 * Collects and exposes Transaction Manager metrics to the {@link MetricsRegistry}.
 */
public final class TransactionMetricsCollector {

    private final MetricsRegistry registry;
    private final TransactionManager transactionManager;
    private final Counter committedCounter;
    private final Counter abortedCounter;

    public TransactionMetricsCollector(MetricsRegistry registry, TransactionManager transactionManager) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");

        this.committedCounter = registry.counter("koradb_transactions_committed_total", "Total successfully committed transactions");
        this.abortedCounter = registry.counter("koradb_transactions_aborted_total", "Total aborted and rolled back transactions");

        registry.gauge("koradb_transactions_active", "Number of currently active transactions",
                transactionManager::activeTransactionCount);
    }

    public void recordCommit() {
        committedCounter.inc();
    }

    public void recordAbort() {
        abortedCounter.inc();
    }

    public Counter getCommittedCounter() {
        return committedCounter;
    }

    public Counter getAbortedCounter() {
        return abortedCounter;
    }
}
