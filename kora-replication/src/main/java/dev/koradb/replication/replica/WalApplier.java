package dev.koradb.replication.replica;

import dev.koradb.buffer.BufferFrame;
import dev.koradb.catalog.Catalog;
import dev.koradb.catalog.Schema;
import dev.koradb.catalog.Table;
import dev.koradb.sql.ast.CreateTableStmt;
import dev.koradb.sql.ast.Statement;
import dev.koradb.sql.lexer.Lexer;
import dev.koradb.sql.lexer.Token;
import dev.koradb.sql.parser.Parser;
import dev.koradb.storage.SlottedPage;
import dev.koradb.storage.TableFile;
import dev.koradb.storage.wal.*;
import dev.koradb.transaction.TransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Applies Write-Ahead Log records received from the Primary onto the Replica's local storage and catalog.
 */
public final class WalApplier {

    private static final Logger log = LoggerFactory.getLogger(WalApplier.class);

    private final Catalog catalog;
    private final TransactionManager transactionManager;
    private final Function<String, TableFile> tableFileResolver;
    private final AtomicLong appliedLsn = new AtomicLong(LogManager.WAL_HEADER_SIZE);

    public WalApplier(Catalog catalog,
                      TransactionManager transactionManager,
                      Function<String, TableFile> tableFileResolver) {
        this.catalog = catalog;
        this.transactionManager = transactionManager;
        this.tableFileResolver = Objects.requireNonNull(tableFileResolver, "tableFileResolver must not be null");
    }

    /**
     * Replays a list of WAL records in sequential LSN order.
     *
     * @param records list of records to apply
     * @return the number of applied records
     */
    public int applyRecords(List<LogRecord> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }

        int appliedCount = 0;
        for (LogRecord record : records) {
            applyRecord(record);
            long endLsn = record.getLsn() + LogRecordCodec.calculateEncodedLength(record);
            appliedLsn.accumulateAndGet(endLsn, Math::max);
            appliedCount++;
        }
        return appliedCount;
    }

    private void applyRecord(LogRecord record) {
        if (transactionManager != null && record.getTxnId() > 0L) {
            transactionManager.updateNextTxnId(record.getTxnId() + 1);
        }

        switch (record) {
            case UpdateLogRecord update -> applyUpdate(update);
            case ClrLogRecord clr -> applyClr(clr);
            case CommitLogRecord commit -> {
                if (transactionManager != null) {
                    transactionManager.recordCommitted(commit.getTxnId());
                    transactionManager.updateNextTxnId(commit.getTxnId() + 1);
                }
            }
            case AbortLogRecord abort -> {
                if (transactionManager != null) {
                    transactionManager.recordAborted(abort.getTxnId());
                    transactionManager.updateNextTxnId(abort.getTxnId() + 1);
                }
            }
            case DdlLogRecord ddl -> applyDdl(ddl);
            default -> {
                // BEGIN, CHECKPOINT_BEGIN, CHECKPOINT_END, END records require no direct page mutations
            }
        }
    }

    private void applyUpdate(UpdateLogRecord update) {
        TableFile tf = tableFileResolver.apply(update.getTableName());
        if (tf == null) {
            log.warn("Cannot apply update at LSN {}: table '{}' not found", update.getLsn(), update.getTableName());
            return;
        }

        // Allocate pages if needed
        while (tf.pageCount() <= update.getPageId()) {
            BufferFrame newFrame = tf.getBufferPool().allocatePage();
            tf.getBufferPool().unpin(newFrame);
        }

        BufferFrame frame = tf.getBufferPool().pin(update.getPageId());
        try {
            SlottedPage sp = new SlottedPage(frame.getPage());
            byte[] afterImage = update.getAfterImage();
            if (afterImage != null && afterImage.length > 0) {
                if (update.getSlotIndex() < sp.slotCount()) {
                    sp.updateRecord(update.getSlotIndex(), afterImage);
                } else {
                    sp.insertRecord(afterImage);
                }
            }
            frame.getPage().setPageLsn(update.getLsn());
            tf.getBufferPool().markDirty(frame);
        } finally {
            tf.getBufferPool().unpin(frame);
        }
    }

    private void applyClr(ClrLogRecord clr) {
        TableFile tf = tableFileResolver.apply(clr.getTableName());
        if (tf == null) {
            return;
        }

        BufferFrame frame = tf.getBufferPool().pin(clr.getPageId());
        try {
            SlottedPage sp = new SlottedPage(frame.getPage());
            if (clr.getRestoredImage() != null && clr.getRestoredImage().length > 0) {
                sp.updateRecord(clr.getSlotIndex(), clr.getRestoredImage());
            }
            frame.getPage().setPageLsn(clr.getLsn());
            tf.getBufferPool().markDirty(frame);
        } finally {
            tf.getBufferPool().unpin(frame);
        }
    }

    private void applyDdl(DdlLogRecord ddl) {
        if (catalog == null) {
            return;
        }
        String sql = ddl.getSql().trim();
        try {
            List<Token> tokens = new Lexer(sql).tokenize();
            Statement stmt = new Parser(tokens).parse();
            if (stmt instanceof CreateTableStmt cts) {
                Schema schema = new Schema(cts.columnDefs());
                Table table = new Table(cts.tableName(), schema);
                try {
                    catalog.createTable(table);
                    log.info("Applied DDL CREATE TABLE '{}' on replica", cts.tableName());
                } catch (Exception e) {
                    log.debug("DDL CREATE TABLE ignored or already exists: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Error applying DDL at LSN {}: '{}'", ddl.getLsn(), sql, e);
        }
    }

    public long getAppliedLsn() {
        return appliedLsn.get();
    }

    public void setAppliedLsn(long lsn) {
        appliedLsn.set(lsn);
    }
}
