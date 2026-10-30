package dev.koradb.replication.replica;

import dev.koradb.buffer.BufferFrame;
import dev.koradb.catalog.Catalog;
import dev.koradb.catalog.Row;
import dev.koradb.catalog.Schema;
import dev.koradb.catalog.Table;
import dev.koradb.exception.SchemaException;
import dev.koradb.index.Index;
import dev.koradb.index.IndexKey;
import dev.koradb.sql.ast.AlterTableStmt;
import dev.koradb.sql.ast.CreateIndexStmt;
import dev.koradb.sql.ast.CreateTableStmt;
import dev.koradb.sql.ast.DropIndexStmt;
import dev.koradb.sql.ast.DropTableStmt;
import dev.koradb.sql.lexer.Lexer;
import dev.koradb.sql.parser.Parser;
import dev.koradb.storage.RecordId;
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
        applyImage(update.getTableName(), update.getPageId(), update.getSlotIndex(),
                update.getAfterImage(), update.getLsn());
    }

    /**
     * A CLR restores the before-image of an undone change. An EMPTY restored image means
     * "remove the row this (aborted) transaction inserted": it must be applied physically,
     * otherwise the row survives on the replica and becomes visible after a restart, when
     * the in-memory record of the abort is gone.
     */
    private void applyClr(ClrLogRecord clr) {
        applyImage(clr.getTableName(), clr.getPageId(), clr.getSlotIndex(),
                clr.getRestoredImage(), clr.getLsn());
    }

    /** Applies one page change idempotently (skipped when the page already has this LSN). */
    private void applyImage(String tableName, int pageId, int slot, byte[] image, long lsn) {
        TableFile tf = tableFileResolver.apply(tableName);
        if (tf == null) {
            log.warn("Cannot apply WAL record at LSN {}: table '{}' not found", lsn, tableName);
            return;
        }
        while (tf.pageCount() <= pageId) {
            BufferFrame newFrame = tf.getBufferPool().allocatePage();
            tf.getBufferPool().unpin(newFrame);
        }
        BufferFrame frame = tf.getBufferPool().pin(pageId);
        try {
            if (frame.getPage().getPageLsn() >= lsn) {
                return; // already applied (catch-up overlap / re-delivery)
            }
            SlottedPage sp = new SlottedPage(frame.getPage());
            List<Index> indexes = catalog != null ? catalog.getIndexesForTable(tableName) : List.of();
            // Read before the change: the image may replace it in the page
            Row previous = !indexes.isEmpty() && slot < sp.slotCount() && sp.isAlive(slot) ? tf.decodeImage(sp.getRecord(slot)) : null;
            if (image == null || image.length == 0) {
                if (sp.isAlive(slot)) {
                    sp.deleteRecord(slot);
                }
            } else if (slot < sp.slotCount()) {
                sp.updateRecord(slot, image);
            } else if (slot == sp.slotCount()) {
                sp.insertRecord(image);
            } else {
                log.warn("WAL record at LSN {} targets slot {} beyond page {} slot count {}", lsn, slot, pageId, sp.slotCount());
            }
            frame.getPage().setPageLsn(lsn);
            tf.getBufferPool().markDirty(frame);
            if (!indexes.isEmpty() && image != null && image.length > 0) {
                addIndexEntries(indexes, previous, tf.decodeImage(image), new RecordId(pageId, slot));
            }
        } finally {
            tf.getBufferPool().unpin(frame);
        }
    }

    /**
     * Gives a stored version its index entries. Stamping xmax on a row rewrites it with the same key,
     * and the entry from when it was written still applies. Entries of removed rows stay, as on the
     * primary: they are hints that index readers re-check against the page.
     */
    private void addIndexEntries(List<Index> indexes, Row previous, Row stored, RecordId rid) {
        for (Index index : indexes) {
            Object key = stored.get(index.getColumnName());
            if (key != null && (previous == null || !key.equals(previous.get(index.getColumnName())))) {
                index.insertUnchecked(new IndexKey(key, index.getKeyType()), rid);
            }
        }
    }

    /**
     * Applies a schema change. A replica receives the whole WAL again when it restarts, so a statement
     * may already be in effect: that is the expected SchemaException ("already exists", "does not
     * exist"). Replaying a DROP followed by a CREATE removes the table's file, and the records after
     * them then rebuild it.
     */
    private void applyDdl(DdlLogRecord ddl) {
        if (catalog == null) {
            return;
        }
        String sql = ddl.getSql().trim();
        try {
            switch (new Parser(new Lexer(sql).tokenize()).parse()) {
                case CreateTableStmt s -> catalog.createTable(new Table(s.tableName(), new Schema(s.columnDefs())));
                case AlterTableStmt s -> catalog.addColumn(s.tableName(), s.addColumn());
                case DropTableStmt s -> catalog.dropTable(s.tableName());
                case CreateIndexStmt s -> catalog.createIndex(s.indexName(), s.tableName(), s.columnName(), s.unique());
                case DropIndexStmt s -> catalog.dropIndex(s.indexName());
                default -> {
                    log.warn("Ignoring unexpected DDL at LSN {}: '{}'", ddl.getLsn(), sql);
                    return;
                }
            }
            log.info("Applied DDL on replica: {}", sql);
        } catch (SchemaException e) {
            log.debug("DDL already in effect: {} ({})", sql, e.getMessage());
        } catch (Exception e) {
            log.error("Error applying DDL at LSN {}: '{}'", ddl.getLsn(), sql, e);
        }
    }

    public long getAppliedLsn() {
        return appliedLsn.get();
    }

    public void setAppliedLsn(long lsn) {
        appliedLsn.set(lsn);
    }
}
