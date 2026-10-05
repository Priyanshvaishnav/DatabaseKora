package dev.koradb.storage.recovery;

import dev.koradb.storage.TableFile;
import dev.koradb.storage.wal.LogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * ARIES Crash Recovery Manager.
 *
 * <p>Coordinates the 3-phase ARIES recovery process:
 * <ol>
 *   <li><b>Analysis Pass:</b> Scans log from checkpoint to reconstruct Active Transaction Table (ATT)
 *       and Dirty Page Table (DPT), identifying winner transactions and loser transactions.</li>
 *   <li><b>Redo Pass:</b> "Repeats history" starting at {@code min(recLSN)}, replaying logged mutations
 *       and CLRs for pages where {@code pageLSN < recordLSN}.</li>
 *   <li><b>Undo Pass:</b> Rolls back all loser transactions in reverse chronological order across all losers,
 *       emitting Compensation Log Records (CLRs) with {@code undoNextLSN} pointers.</li>
 * </ol>
 */
public final class RecoveryManager {

    private static final Logger log = LoggerFactory.getLogger(RecoveryManager.class);

    private final Path walPath;
    private final LogManager logManager;
    private final Function<String, TableFile> tableFileResolver;
    private final Supplier<Collection<TableFile>> openTablesSupplier;

    public RecoveryManager(Path walPath,
                           LogManager logManager,
                           Function<String, TableFile> tableFileResolver) {
        this(walPath, logManager, tableFileResolver, Collections::emptyList);
    }

    public RecoveryManager(Path walPath,
                           LogManager logManager,
                           Function<String, TableFile> tableFileResolver,
                           Supplier<Collection<TableFile>> openTablesSupplier) {
        this.walPath = Objects.requireNonNull(walPath, "walPath must not be null");
        this.logManager = Objects.requireNonNull(logManager, "logManager must not be null");
        this.tableFileResolver = Objects.requireNonNull(tableFileResolver, "tableFileResolver must not be null");
        this.openTablesSupplier = openTablesSupplier != null ? openTablesSupplier : Collections::emptyList;
    }

    /**
     * Checks if the WAL contains records that need recovery analysis.
     */
    public boolean needsRecovery() {
        if (!Files.exists(walPath)) {
            return false;
        }
        try {
            long size = Files.size(walPath);
            return size > LogManager.WAL_HEADER_SIZE;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Executes full 3-phase ARIES crash recovery.
     *
     * @return summary result of the recovery execution
     */
    public RecoveryResult recover() {
        if (!needsRecovery()) {
            log.info("No WAL records found at {}; skipping recovery", walPath);
            return new RecoveryResult(
                    new AnalysisResult(0, 0, Map.of(), Map.of(), Set.of(), Set.of()),
                    0, 0, 0
            );
        }

        long startTime = System.currentTimeMillis();
        log.info("Starting ARIES crash recovery on {}", walPath);

        // Phase 1: Analysis Pass
        AnalysisPass analysisPass = new AnalysisPass(walPath);
        AnalysisResult analysis = analysisPass.execute();

        // Phase 2: Redo Pass ("repeating history")
        RedoPass redoPass = new RedoPass(walPath, tableFileResolver);
        int redoneCount = redoPass.execute(analysis);

        // Phase 3: Undo Pass
        UndoPass undoPass = new UndoPass(walPath, logManager, tableFileResolver);
        int undoneCount = undoPass.execute(analysis);

        // Post-recovery: Flush all open buffer pools to disk
        Collection<TableFile> tables = openTablesSupplier.get();
        if (tables != null) {
            for (TableFile tf : tables) {
                try {
                    tf.getBufferPool().flushAll();
                } catch (Exception e) {
                    log.warn("Error flushing buffer pool for table '{}'", tf, e);
                }
            }
        }

        // Flush WAL
        logManager.flush(logManager.getNextLsn());

        long durationMs = System.currentTimeMillis() - startTime;
        RecoveryResult result = new RecoveryResult(analysis, redoneCount, undoneCount, durationMs);
        log.info("ARIES crash recovery completed in {}ms: {}", durationMs, result);
        return result;
    }
}
