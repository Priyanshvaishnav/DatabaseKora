package dev.koradb.storage.recovery;

import dev.koradb.buffer.BufferFrame;
import dev.koradb.storage.Page;
import dev.koradb.storage.SlottedPage;
import dev.koradb.storage.TableFile;
import dev.koradb.storage.wal.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Phase 2 of ARIES Crash Recovery: Redo Pass ("Repeating History").
 *
 * <p>Scans forward from {@code redoStartLSN} to the end of the log and reapplies
 * all logged operations (both committed and uncommitted) to restore the exact state
 * of all pages at the time of the crash.
 *
 * <p>Uses {@code pageLSN} to skip redundant I/O for changes already written to disk.
 */
public final class RedoPass {

    private static final Logger log = LoggerFactory.getLogger(RedoPass.class);

    private final Path walPath;
    private final Function<String, TableFile> tableFileResolver;

    public RedoPass(Path walPath, Function<String, TableFile> tableFileResolver) {
        this.walPath = Objects.requireNonNull(walPath, "walPath must not be null");
        this.tableFileResolver = Objects.requireNonNull(tableFileResolver, "tableFileResolver must not be null");
    }

    public int execute(AnalysisResult analysisResult) {
        log.info("Starting ARIES Redo Pass from LSN {}", analysisResult.getRedoStartLsn());
        int redoneCount = 0;

        Map<PageKey, Long> dpt = analysisResult.getDirtyPageTable();
        long startLsn = Math.max(analysisResult.getRedoStartLsn(), LogManager.WAL_HEADER_SIZE);

        try (LogIterator it = new LogIterator(walPath, startLsn)) {
            while (it.hasNext()) {
                LogRecord record = it.next();

                if (record instanceof UpdateLogRecord update) {
                    redoneCount += redoUpdate(update, dpt);
                } else if (record instanceof ClrLogRecord clr) {
                    redoneCount += redoClr(clr, dpt);
                }
            }
        }

        log.info("ARIES Redo Pass complete: {} record(s) reapplied", redoneCount);
        return redoneCount;
    }

    private int redoUpdate(UpdateLogRecord update, Map<PageKey, Long> dpt) {
        PageKey key = new PageKey(update.getTableName(), update.getPageId());

        // Check 1: Is page in Dirty Page Table?
        Long recLsn = dpt.get(key);
        if (recLsn == null) {
            log.trace("Skipping redo of LSN {}: page {} not in DPT", update.getLsn(), key);
            return 0;
        }

        // Check 2: Did record occur before recLSN?
        if (update.getLsn() < recLsn) {
            log.trace("Skipping redo of LSN {}: LSN < recLSN {}", update.getLsn(), recLsn);
            return 0;
        }

        TableFile tf = tableFileResolver.apply(update.getTableName());
        if (tf == null) {
            log.warn("Cannot redo update at LSN {}: table '{}' not found", update.getLsn(), update.getTableName());
            return 0;
        }

        // Ensure page exists in table file
        while (tf.pageCount() <= update.getPageId()) {
            BufferFrame newFrame = tf.getBufferPool().allocatePage();
            tf.getBufferPool().unpin(newFrame);
        }

        BufferFrame frame = tf.getBufferPool().pin(update.getPageId());
        try {
            Page page = frame.getPage();

            // Check 3: Has page on disk already reached or surpassed this LSN?
            if (page.getPageLsn() >= update.getLsn()) {
                log.trace("Skipping redo of LSN {}: pageLSN {} >= recordLSN", update.getLsn(), page.getPageLsn());
                return 0;
            }

            // Apply redo
            SlottedPage sp = new SlottedPage(page);
            byte[] after = update.getAfterImage();

            if (after == null || after.length == 0) {
                // DELETE
                if (sp.isAlive(update.getSlotIndex())) {
                    sp.deleteRecord(update.getSlotIndex());
                }
            } else {
                // INSERT or UPDATE
                if (update.getSlotIndex() == sp.slotCount()) {
                    sp.insertRecord(after);
                } else if (update.getSlotIndex() < sp.slotCount()) {
                    sp.updateRecord(update.getSlotIndex(), after);
                }
            }

            page.setPageLsn(update.getLsn());
            tf.getBufferPool().markDirty(frame, update.getLsn());
            log.debug("Redone UPDATE at LSN {} on table='{}', page={}, slot={}",
                    update.getLsn(), update.getTableName(), update.getPageId(), update.getSlotIndex());
            return 1;
        } finally {
            tf.getBufferPool().unpin(frame);
        }
    }

    private int redoClr(ClrLogRecord clr, Map<PageKey, Long> dpt) {
        PageKey key = new PageKey(clr.getTableName(), clr.getPageId());

        Long recLsn = dpt.get(key);
        if (recLsn == null || clr.getLsn() < recLsn) {
            return 0;
        }

        TableFile tf = tableFileResolver.apply(clr.getTableName());
        if (tf == null) {
            return 0;
        }

        while (tf.pageCount() <= clr.getPageId()) {
            BufferFrame newFrame = tf.getBufferPool().allocatePage();
            tf.getBufferPool().unpin(newFrame);
        }

        BufferFrame frame = tf.getBufferPool().pin(clr.getPageId());
        try {
            Page page = frame.getPage();
            if (page.getPageLsn() >= clr.getLsn()) {
                return 0;
            }

            SlottedPage sp = new SlottedPage(page);
            byte[] restored = clr.getRestoredImage();

            if (restored == null || restored.length == 0) {
                if (sp.isAlive(clr.getSlotIndex())) {
                    sp.deleteRecord(clr.getSlotIndex());
                }
            } else {
                if (clr.getSlotIndex() == sp.slotCount()) {
                    sp.insertRecord(restored);
                } else if (clr.getSlotIndex() < sp.slotCount()) {
                    sp.updateRecord(clr.getSlotIndex(), restored);
                }
            }

            page.setPageLsn(clr.getLsn());
            tf.getBufferPool().markDirty(frame, clr.getLsn());
            log.debug("Redone CLR at LSN {} on table='{}', page={}, slot={}",
                    clr.getLsn(), clr.getTableName(), clr.getPageId(), clr.getSlotIndex());
            return 1;
        } finally {
            tf.getBufferPool().unpin(frame);
        }
    }
}
