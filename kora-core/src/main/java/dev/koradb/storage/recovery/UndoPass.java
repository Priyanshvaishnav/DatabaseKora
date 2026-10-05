package dev.koradb.storage.recovery;

import dev.koradb.buffer.BufferFrame;
import dev.koradb.storage.Page;
import dev.koradb.storage.SlottedPage;
import dev.koradb.storage.TableFile;
import dev.koradb.storage.wal.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.Function;

/**
 * Phase 3 of ARIES Crash Recovery: Undo Pass.
 *
 * <p>Scans backward through the WAL across all loser transactions using a max-heap
 * PriorityQueue on LSN. Reverses active loser updates and writes Compensation Log
 * Records (CLRs) with {@code undoNextLSN} pointers to ensure idempotency.
 */
public final class UndoPass {

    private static final Logger log = LoggerFactory.getLogger(UndoPass.class);

    private final Path walPath;
    private final LogManager logManager;
    private final Function<String, TableFile> tableFileResolver;

    private record UndoEntry(long lsn, long txnId) {}

    public UndoPass(Path walPath, LogManager logManager, Function<String, TableFile> tableFileResolver) {
        this.walPath = Objects.requireNonNull(walPath, "walPath must not be null");
        this.logManager = Objects.requireNonNull(logManager, "logManager must not be null");
        this.tableFileResolver = Objects.requireNonNull(tableFileResolver, "tableFileResolver must not be null");
    }

    public int execute(AnalysisResult analysisResult) {
        Set<Long> losers = analysisResult.getLoserTxnIds();
        log.info("Starting ARIES Undo Pass for {} loser transaction(s): {}", losers.size(), losers);

        if (losers.isEmpty()) {
            return 0;
        }

        // Max-heap: highest LSN processed first (reverse chronological order across all losers)
        PriorityQueue<UndoEntry> queue = new PriorityQueue<>(Comparator.comparingLong(UndoEntry::lsn).reversed());

        for (long loserId : losers) {
            TransactionRecoveryInfo info = analysisResult.getTransactionTable().get(loserId);
            if (info != null && info.getUndoNextLsn() > 0) {
                queue.add(new UndoEntry(info.getUndoNextLsn(), loserId));
            }
        }

        int undoneCount = 0;

        try (FileChannel channel = FileChannel.open(walPath, StandardOpenOption.READ)) {
            while (!queue.isEmpty()) {
                UndoEntry entry = queue.poll();
                LogRecord record = readRecordAt(channel, entry.lsn());

                if (record instanceof UpdateLogRecord update) {
                    undoneCount += undoUpdate(update, queue);
                } else if (record instanceof ClrLogRecord clr) {
                    // CLRs are skipped; follow undoNextLSN
                    if (clr.getUndoNextLsn() > 0) {
                        queue.add(new UndoEntry(clr.getUndoNextLsn(), clr.getTxnId()));
                    } else {
                        logManager.appendEnd(clr.getTxnId(), clr.getLsn());
                    }
                } else if (record instanceof BeginLogRecord begin) {
                    // Reached transaction start — write END
                    logManager.appendEnd(begin.getTxnId(), begin.getLsn());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error accessing WAL during Undo Pass: " + walPath, e);
        }

        // Force WAL flush so CLRs and END records are persisted
        logManager.flush(logManager.getNextLsn());

        log.info("ARIES Undo Pass complete: {} operation(s) undone", undoneCount);
        return undoneCount;
    }

    private int undoUpdate(UpdateLogRecord update, PriorityQueue<UndoEntry> queue) {
        TableFile tf = tableFileResolver.apply(update.getTableName());
        if (tf == null) {
            log.warn("Cannot undo update at LSN {}: table '{}' not found", update.getLsn(), update.getTableName());
            return 0;
        }

        BufferFrame frame = tf.getBufferPool().pin(update.getPageId());
        try {
            Page page = frame.getPage();
            SlottedPage sp = new SlottedPage(page);

            byte[] before = update.getBeforeImage();

            if (before == null || before.length == 0) {
                // Undo an INSERT: delete the record
                if (sp.isAlive(update.getSlotIndex())) {
                    sp.deleteRecord(update.getSlotIndex());
                }
            } else {
                // Undo a DELETE or UPDATE: restore before-image
                if (update.getSlotIndex() == sp.slotCount()) {
                    sp.insertRecord(before);
                } else if (update.getSlotIndex() < sp.slotCount()) {
                    sp.updateRecord(update.getSlotIndex(), before);
                }
            }

            // Write CLR
            long undoNextLsn = update.getPrevLsn();
            long clrLsn = logManager.appendClr(
                    update.getTxnId(),
                    update.getLsn(),
                    update.getTableName(),
                    update.getPageId(),
                    update.getSlotIndex(),
                    before,
                    undoNextLsn
            );

            page.setPageLsn(clrLsn);
            tf.getBufferPool().markDirty(frame, clrLsn);

            if (undoNextLsn > 0) {
                queue.add(new UndoEntry(undoNextLsn, update.getTxnId()));
            } else {
                logManager.appendEnd(update.getTxnId(), clrLsn);
            }

            log.debug("Undone UPDATE at LSN {}, written CLR at LSN {} (undoNextLSN={})",
                    update.getLsn(), clrLsn, undoNextLsn);
            return 1;
        } finally {
            tf.getBufferPool().unpin(frame);
        }
    }

    private LogRecord readRecordAt(FileChannel channel, long lsn) {
        try {
            ByteBuffer lenBuf = ByteBuffer.allocate(4);
            channel.read(lenBuf, lsn);
            lenBuf.flip();
            int totalLength = lenBuf.getInt();

            ByteBuffer recordBuf = ByteBuffer.allocate(totalLength);
            channel.read(recordBuf, lsn);
            recordBuf.flip();
            return LogRecordCodec.decode(recordBuf);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read log record at LSN " + lsn + " from " + walPath, e);
        }
    }
}
