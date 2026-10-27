package dev.koradb.mvcc;

import dev.koradb.catalog.Catalog;
import dev.koradb.catalog.Table;
import dev.koradb.transaction.Transaction;
import dev.koradb.transaction.TransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Objects;

/**
 * Garbage collection and dead version pruning engine for MVCC.
 *
 * <p>Identifies tuple versions where {@code xmax > 0} and {@code xmax < minActiveTxnId},
 * meaning the version has been deleted/superseded and is no longer visible to any
 * active or future transaction. Purges them to reclaim memory and disk page space.
 */
public final class VacuumManager {

    private static final Logger log = LoggerFactory.getLogger(VacuumManager.class);

    private final Catalog catalog;
    private final TransactionManager transactionManager;

    public VacuumManager(Catalog catalog, TransactionManager transactionManager) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.transactionManager = transactionManager;
    }

    /**
     * Executes vacuum across all tables in the current active database.
     *
     * @return the total number of dead row versions reclaimed
     */
    public int vacuum() {
        long lowWatermark;
        if (transactionManager != null) {
            Collection<Transaction> active = transactionManager.getActiveTransactions();
            lowWatermark = active.isEmpty()
                    ? transactionManager.getNextTxnId()
                    : active.stream().map(t -> t.getId().value()).min(Long::compareTo).orElse(transactionManager.getNextTxnId());
        } else {
            lowWatermark = Long.MAX_VALUE;
        }

        int totalReclaimed = 0;
        for (Table table : catalog.listTables()) {
            int reclaimed = vacuumTable(table, lowWatermark);
            totalReclaimed += reclaimed;
            log.debug("Vacuumed table '{}': {} dead version(s) reclaimed", table.getName(), reclaimed);
        }

        log.info("Vacuum complete: {} dead tuple version(s) reclaimed across all tables (lowWatermark={})",
                totalReclaimed, lowWatermark);
        return totalReclaimed;
    }

    /**
     * Executes vacuum on a specific table.
     *
     * @param tableName table to vacuum
     * @return number of dead row versions reclaimed
     */
    /** Vacuums one table and drops index entries that point at the reclaimed versions. */
    private int vacuumTable(Table table, long lowWatermark) {
        java.util.function.Predicate<Long> committed = transactionManager != null ? transactionManager::isCommitted : id -> true;
        if (table instanceof dev.koradb.catalog.DiskTable dt) {
            java.util.List<dev.koradb.storage.RecordId> reclaimedRids = new java.util.ArrayList<>();
            int n = dt.getTableFile().vacuum(lowWatermark, committed, reclaimedRids);
            if (!reclaimedRids.isEmpty()) {
                java.util.Set<dev.koradb.storage.RecordId> set = new java.util.HashSet<>(reclaimedRids);
                for (dev.koradb.index.Index idx : catalog.getIndexesForTable(table.getName())) {
                    idx.removeEntries(set);
                }
            }
            return n;
        }
        return table.vacuum(lowWatermark, committed);
    }

    public int vacuum(String tableName) {
        Table table = catalog.getTable(tableName);
        long lowWatermark;
        if (transactionManager != null) {
            Collection<Transaction> active = transactionManager.getActiveTransactions();
            lowWatermark = active.isEmpty()
                    ? transactionManager.getNextTxnId()
                    : active.stream().map(t -> t.getId().value()).min(Long::compareTo).orElse(transactionManager.getNextTxnId());
        } else {
            lowWatermark = Long.MAX_VALUE;
        }

        int reclaimed = vacuumTable(table, lowWatermark);
        log.info("Vacuumed table '{}': {} dead version(s) reclaimed", tableName, reclaimed);
        return reclaimed;
    }
}
