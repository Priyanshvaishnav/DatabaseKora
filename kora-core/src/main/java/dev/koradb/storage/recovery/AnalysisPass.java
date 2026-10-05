package dev.koradb.storage.recovery;

import dev.koradb.storage.wal.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Phase 1 of ARIES Crash Recovery: Analysis Pass.
 *
 * <p>Scans the log forward from the most recent checkpoint (or log beginning if none exists)
 * to determine:
 * <ol>
 *   <li>The state of transactions at crash time (identifying Winners vs Losers).</li>
 *   <li>The Dirty Page Table (DPT) identifying which pages were dirty in buffer pool.</li>
 *   <li>The Redo Start LSN: the minimum {@code recLSN} across all dirty pages.</li>
 * </ol>
 */
public final class AnalysisPass {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPass.class);

    private final Path walPath;

    public AnalysisPass(Path walPath) {
        this.walPath = Objects.requireNonNull(walPath, "walPath must not be null");
    }

    public AnalysisResult execute() {
        log.info("Starting ARIES Analysis Pass on {}", walPath);

        // 1. First scan: locate the latest complete checkpoint in WAL
        long lastCheckpointBeginLsn = 0L;
        long lastCheckpointEndLsn = 0L;
        CheckpointEndRecord latestCheckpointEnd = null;

        try (LogIterator it = new LogIterator(walPath)) {
            while (it.hasNext()) {
                LogRecord record = it.next();
                if (record instanceof CheckpointBeginRecord cpBegin) {
                    lastCheckpointBeginLsn = cpBegin.getLsn();
                } else if (record instanceof CheckpointEndRecord cpEnd) {
                    lastCheckpointEndLsn = cpEnd.getLsn();
                    latestCheckpointEnd = cpEnd;
                }
            }
        }

        // 2. Initialize ATT and DPT
        Map<Long, TransactionRecoveryInfo> transactionTable = new LinkedHashMap<>();
        Map<PageKey, Long> dirtyPageTable = new LinkedHashMap<>();
        long checkpointRecLsnMin = Long.MAX_VALUE;

        long scanStartLsn = LogManager.WAL_HEADER_SIZE;
        if (latestCheckpointEnd != null && lastCheckpointBeginLsn > 0) {
            log.info("Found checkpoint at beginLSN={}, endLSN={}", lastCheckpointBeginLsn, lastCheckpointEndLsn);
            // Seed Transaction Table from checkpoint snapshot
            for (Map.Entry<Long, Long> entry : latestCheckpointEnd.getActiveTransactionTable().entrySet()) {
                transactionTable.put(entry.getKey(),
                        new TransactionRecoveryInfo(entry.getKey(), TransactionStatus.ACTIVE, entry.getValue()));
            }

            // Seed minimum recLSN from checkpoint DPT
            for (long recLsn : latestCheckpointEnd.getDirtyPageTable().values()) {
                if (recLsn > 0 && recLsn < checkpointRecLsnMin) {
                    checkpointRecLsnMin = recLsn;
                }
            }

            // In ARIES, analysis scan starts from the CheckpointBegin record
            scanStartLsn = lastCheckpointBeginLsn;
        } else {
            log.info("No checkpoint found; scanning WAL from start");
        }

        // 3. Scan forward from scanStartLsn to EOF
        try (LogIterator it = new LogIterator(walPath, scanStartLsn)) {
            while (it.hasNext()) {
                LogRecord record = it.next();
                long lsn = record.getLsn();
                long txnId = record.getTxnId();

                switch (record.getType()) {
                    case BEGIN -> {
                        transactionTable.put(txnId, new TransactionRecoveryInfo(txnId, TransactionStatus.ACTIVE, lsn));
                    }
                    case UPDATE -> {
                        UpdateLogRecord u = (UpdateLogRecord) record;
                        TransactionRecoveryInfo info = transactionTable.computeIfAbsent(
                                txnId, id -> new TransactionRecoveryInfo(id, TransactionStatus.ACTIVE, lsn));
                        info.setLastLsn(lsn);
                        info.setUndoNextLsn(lsn);

                        PageKey key = new PageKey(u.getTableName(), u.getPageId());
                        dirtyPageTable.putIfAbsent(key, lsn);
                    }
                    case CLR -> {
                        ClrLogRecord clr = (ClrLogRecord) record;
                        TransactionRecoveryInfo info = transactionTable.computeIfAbsent(
                                txnId, id -> new TransactionRecoveryInfo(id, TransactionStatus.ACTIVE, lsn));
                        info.setLastLsn(lsn);
                        info.setUndoNextLsn(clr.getUndoNextLsn());

                        PageKey key = new PageKey(clr.getTableName(), clr.getPageId());
                        dirtyPageTable.putIfAbsent(key, lsn);
                    }
                    case COMMIT -> {
                        TransactionRecoveryInfo info = transactionTable.get(txnId);
                        if (info != null) {
                            info.setStatus(TransactionStatus.COMMITTED);
                            info.setLastLsn(lsn);
                        } else {
                            transactionTable.put(txnId, new TransactionRecoveryInfo(txnId, TransactionStatus.COMMITTED, lsn));
                        }
                    }
                    case ABORT -> {
                        TransactionRecoveryInfo info = transactionTable.get(txnId);
                        if (info != null) {
                            info.setStatus(TransactionStatus.ABORTED);
                            info.setLastLsn(lsn);
                        } else {
                            transactionTable.put(txnId, new TransactionRecoveryInfo(txnId, TransactionStatus.ABORTED, lsn));
                        }
                    }
                    case END -> {
                        transactionTable.remove(txnId);
                    }
                    case CHECKPOINT_BEGIN, CHECKPOINT_END -> {
                        // Metadata checkpoints handled
                    }
                }
            }
        }

        // 4. Partition Winners and Losers
        Set<Long> loserTxnIds = new LinkedHashSet<>();
        Set<Long> winnerTxnIds = new LinkedHashSet<>();

        for (TransactionRecoveryInfo info : transactionTable.values()) {
            if (info.getStatus() == TransactionStatus.COMMITTED) {
                winnerTxnIds.add(info.getTxnId());
            } else {
                loserTxnIds.add(info.getTxnId());
            }
        }

        // 5. Determine redoStartLSN
        long minDptRecLsn = dirtyPageTable.values().stream().min(Long::compareTo).orElse(Long.MAX_VALUE);
        long effectiveMinRecLsn = Math.min(minDptRecLsn, checkpointRecLsnMin);

        long redoStartLsn;
        if (effectiveMinRecLsn != Long.MAX_VALUE) {
            redoStartLsn = effectiveMinRecLsn;
        } else if (lastCheckpointBeginLsn > 0) {
            redoStartLsn = lastCheckpointBeginLsn;
        } else {
            redoStartLsn = LogManager.WAL_HEADER_SIZE;
        }

        AnalysisResult result = new AnalysisResult(
                lastCheckpointEndLsn,
                redoStartLsn,
                transactionTable,
                dirtyPageTable,
                loserTxnIds,
                winnerTxnIds
        );

        log.info("Analysis pass complete: redoStartLSN={}, losers={}, winners={}, dirtyPages={}",
                redoStartLsn, loserTxnIds, winnerTxnIds, dirtyPageTable.size());
        return result;
    }
}
