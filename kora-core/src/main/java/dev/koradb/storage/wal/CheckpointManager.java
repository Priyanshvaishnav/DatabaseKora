package dev.koradb.storage.wal;

import dev.koradb.buffer.BufferPool;
import dev.koradb.storage.TableFile;
import dev.koradb.transaction.Transaction;
import dev.koradb.transaction.TransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;

/**
 * Coordinates periodic checkpoints for the Write-Ahead Logging engine.
 *
 * <h2>Checkpoint Process (ARIES-style)</h2>
 * <ol>
 *   <li>Appends a {@link CheckpointBeginRecord} to the WAL.</li>
 *   <li>Flushes dirty frames from active buffer pools to disk (enforcing the WAL invariant).</li>
 *   <li>Collects a snapshot of the Transaction Table (activeTxnId -> prevLSN).</li>
 *   <li>Collects a snapshot of the Dirty Page Table (pageId -> recLSN).</li>
 *   <li>Appends a {@link CheckpointEndRecord} to the WAL.</li>
 *   <li>Flushes the WAL up to {@code endLsn} to ensure durability.</li>
 * </ol>
 */
public final class CheckpointManager {

    private static final Logger log = LoggerFactory.getLogger(CheckpointManager.class);

    private final LogManager logManager;
    private final TransactionManager transactionManager;
    private final Supplier<Collection<TableFile>> openTablesSupplier;

    public CheckpointManager(LogManager logManager,
                             TransactionManager transactionManager,
                             Supplier<Collection<TableFile>> openTablesSupplier) {
        this.logManager = Objects.requireNonNull(logManager, "logManager must not be null");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.openTablesSupplier = openTablesSupplier != null ? openTablesSupplier : Collections::emptyList;
    }

    /**
     * Executes a checkpoint and returns the LSN of the CheckpointEnd record.
     *
     * @return the LSN of the persisted CheckpointEnd record
     */
    public synchronized long checkpoint() {
        log.info("Starting WAL checkpoint");
        long beginTimestamp = System.currentTimeMillis();

        // 1. Write CheckpointBeginRecord
        long beginLsn = logManager.appendCheckpointBegin(beginTimestamp);

        // 2. Flush dirty frames from all buffer pools to disk
        Map<Integer, Long> dirtyPages = new LinkedHashMap<>();
        Collection<TableFile> openTables = openTablesSupplier.get();
        if (openTables != null) {
            for (TableFile tf : openTables) {
                BufferPool pool = tf.getBufferPool();
                pool.flushAll();
                dirtyPages.putAll(pool.getDirtyPageTable());
            }
        }

        // 3. Snapshot active transactions
        Map<Long, Long> activeTxns = new LinkedHashMap<>();
        if (transactionManager != null) {
            for (Transaction txn : transactionManager.getActiveTransactions()) {
                if (txn.isActive()) {
                    activeTxns.put(txn.getId().value(), txn.getPrevLsn());
                }
            }
        }

        // 4. Write CheckpointEndRecord
        long endLsn = logManager.appendCheckpointEnd(activeTxns, dirtyPages);

        // 5. Force WAL flush up to CheckpointEndRecord
        logManager.flush(endLsn);

        log.info("WAL checkpoint completed at LSN={} (beginLSN={}, activeTxns={}, dirtyPages={})",
                endLsn, beginLsn, activeTxns.size(), dirtyPages.size());
        return endLsn;
    }

    public LogManager getLogManager() {
        return logManager;
    }

    public TransactionManager getTransactionManager() {
        return transactionManager;
    }
}
