package dev.koradb.execution;

import dev.koradb.catalog.*;
import dev.koradb.exception.ExecutionException;
import dev.koradb.exception.SchemaException;
import dev.koradb.sql.ast.*;
import dev.koradb.storage.RecordId;
import dev.koradb.storage.TableFile;
import dev.koradb.mvcc.MvccContext;
import dev.koradb.mvcc.ReadView;
import dev.koradb.transaction.IsolationLevel;
import dev.koradb.transaction.Transaction;
import dev.koradb.transaction.TransactionManager;
import dev.koradb.transaction.lock.LockManager;
import dev.koradb.transaction.lock.LockResource;
import dev.koradb.transaction.undo.DeleteUndoAction;
import dev.koradb.transaction.undo.InsertUndoAction;
import dev.koradb.transaction.undo.UpdateUndoAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * The main execution engine for KoraDB.
 *
 * <p>Walks the {@link Statement} AST and performs operations against the
 * {@link Catalog}. Each statement type is handled in its own private method.
 *
 * <h2>Design</h2>
 * <p>Using an exhaustive {@code switch} on the sealed {@link Statement} interface
 * ensures the compiler catches any new statement type added to the AST that
 * is not yet handled here.
 */
public final class StatementExecutor {

    private static final Logger log = LoggerFactory.getLogger(StatementExecutor.class);

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final dev.koradb.planner.QueryPlanner queryPlanner = new dev.koradb.planner.QueryPlanner();
    private final dev.koradb.planner.explain.ExplainExecutor explainExecutor = new dev.koradb.planner.explain.ExplainExecutor(queryPlanner);

    /**
     * Executes a parsed SQL statement against the given catalog without transaction/lock context.
     *
     * @param statement the parsed AST node to execute
     * @param catalog   the schema registry and row store
     * @return the result of execution
     */
    public QueryResult execute(Statement statement, Catalog catalog) {
        return execute(statement, catalog, null, null, null);
    }

    /**
     * Executes a parsed SQL statement against the given catalog within the context of a transaction
     * and lock manager.
     *
     * @param statement   the parsed AST node to execute
     * @param catalog     the schema registry and row store
     * @param txn         active transaction (if any)
     * @param lockManager lock manager for SS2PL concurrency control (if any)
     * @return the result of execution
     */
    public QueryResult execute(Statement statement, Catalog catalog, Transaction txn, LockManager lockManager) {
        return execute(statement, catalog, txn, lockManager, null);
    }

    /**
     * Executes a parsed SQL statement against the given catalog within the context of a transaction,
     * lock manager, and transaction manager for MVCC snapshot isolation.
     *
     * @param statement   the parsed AST node to execute
     * @param catalog     the schema registry and row store
     * @param txn         active transaction (if any)
     * @param lockManager lock manager for SS2PL concurrency control (if any)
     * @param txnManager  transaction manager for ReadView snapshots (if any)
     * @return the result of execution
     */
    public QueryResult execute(Statement statement, Catalog catalog, Transaction txn, LockManager lockManager, TransactionManager txnManager) {
        log.debug("Executing: {}", statement.getClass().getSimpleName());

        return switch (statement) {
            case CreateDatabaseStmt s -> executeCreateDatabase(s, catalog);
            case DropDatabaseStmt   s -> executeDropDatabase(s, catalog);
            case UseDatabaseStmt    s -> executeUseDatabase(s, catalog);
            case CreateTableStmt    s -> executeCreateTable(s, catalog, txn, lockManager);
            case DropTableStmt      s -> executeDropTable(s, catalog, txn, lockManager);
            case CreateIndexStmt    s -> executeCreateIndex(s, catalog);
            case DropIndexStmt      s -> executeDropIndex(s, catalog);
            case InsertStmt         s -> executeInsert(s, catalog, txn, lockManager, txnManager);
            case SelectStmt         s -> executeSelect(subqueries(catalog, txn, lockManager, txnManager).resolve(s), catalog, txn, lockManager, txnManager);
            case UpdateStmt         s -> executeUpdate(subqueries(catalog, txn, lockManager, txnManager).resolve(s), catalog, txn, lockManager, txnManager);
            case DeleteStmt         s -> executeDelete(subqueries(catalog, txn, lockManager, txnManager).resolve(s), catalog, txn, lockManager, txnManager);
            case ExplainStmt        s -> executeExplain(s, catalog, txn, lockManager);
            case BeginStmt          s -> QueryResult.ddl();
            case CommitStmt         s -> QueryResult.ddl();
            case RollbackStmt       s -> QueryResult.ddl();
            case ShowStmt           s -> executeShow(s, catalog);
            case TruncateStmt       s -> executeDelete(new DeleteStmt(s.tableName(), Optional.empty()), catalog, txn, lockManager, txnManager);
            case AlterTableStmt     s -> executeAlterTable(s, catalog, txn, lockManager);
        };
    }

    /** Runs uncorrelated subqueries of a statement (each once, in this transaction) and inlines their results. */
    private SubqueryResolver subqueries(Catalog catalog, Transaction txn, LockManager lockManager, TransactionManager txnManager) {
        return new SubqueryResolver(sub -> executeSelect(subqueries(catalog, txn, lockManager, txnManager).resolve(sub),
                catalog, txn, lockManager, txnManager));
    }

    private QueryResult executeExplain(ExplainStmt stmt, Catalog catalog, Transaction txn, LockManager lockManager) {
        if (stmt.statement() instanceof SelectStmt sel) {
            stmt = new ExplainStmt(subqueries(catalog, txn, lockManager, null).resolve(sel), stmt.analyze());
        }
        if (txn != null && lockManager != null && txn.getIsolationLevel() == IsolationLevel.SERIALIZABLE && stmt.statement() instanceof SelectStmt selectStmt) {
            lockManager.acquireShared(txn.getId(), LockResource.table(selectStmt.tableName()));
            if (selectStmt.join().isPresent()) {
                lockManager.acquireShared(txn.getId(), LockResource.table(selectStmt.join().get().tableName()));
            }
        }
        log.debug("Executing EXPLAIN (analyze={})", stmt.analyze());
        return explainExecutor.execute(stmt, catalog);
    }

    // =========================================================================
    // DDL
    // =========================================================================

    private QueryResult executeCreateDatabase(CreateDatabaseStmt stmt, Catalog catalog) {
        if (stmt.ifNotExists() && databaseExists(catalog, stmt.databaseName())) {
            return QueryResult.ddl();
        }
        catalog.createDatabase(stmt.databaseName());
        log.info("Database '{}' created", stmt.databaseName());
        return QueryResult.ddl();
    }

    private QueryResult executeDropDatabase(DropDatabaseStmt stmt, Catalog catalog) {
        if (stmt.ifExists() && !databaseExists(catalog, stmt.databaseName())) {
            return QueryResult.ddl();
        }
        catalog.dropDatabase(stmt.databaseName());
        log.info("Database '{}' dropped", stmt.databaseName());
        return QueryResult.ddl();
    }

    private static boolean databaseExists(Catalog catalog, String name) {
        return catalog.listDatabases().stream().anyMatch(d -> d.equalsIgnoreCase(name));
    }

    private static boolean tableExists(Catalog catalog, String name) {
        try {
            catalog.getTable(name);
            return true;
        } catch (SchemaException e) {
            return false;
        }
    }

    private QueryResult executeUseDatabase(UseDatabaseStmt stmt, Catalog catalog) {
        catalog.useDatabase(stmt.databaseName());
        log.info("Using database '{}'", stmt.databaseName());
        return QueryResult.ddl();
    }

    private QueryResult executeCreateTable(CreateTableStmt stmt, Catalog catalog, Transaction txn, LockManager lockManager) {
        if (stmt.ifNotExists() && tableExists(catalog, stmt.tableName())) {
            return QueryResult.ddl();
        }
        if (txn != null && lockManager != null) {
            lockManager.acquireExclusive(txn.getId(), LockResource.table(stmt.tableName()));
        }
        // constraint indexes: PRIMARY KEY -> "<table>_pkey", UNIQUE -> "<table>_<col>_key"
        Map<String, String> constraintIndexes = new LinkedHashMap<>();
        if (stmt.primaryKey() != null) {
            constraintIndexes.put(stmt.tableName() + "_pkey", stmt.primaryKey());
        }
        for (String u : stmt.uniqueColumns()) {
            if (u.equalsIgnoreCase(stmt.primaryKey())) continue;
            constraintIndexes.putIfAbsent(stmt.tableName() + "_" + u + "_key", u);
        }
        for (String idxName : constraintIndexes.keySet()) {
            if (catalog.getIndex(idxName).isPresent()) {
                throw new SchemaException("Index '%s' (for a constraint of table '%s') already exists".formatted(idxName, stmt.tableName()));
            }
        }
        Schema schema = new Schema(stmt.columnDefs());
        Table  table  = new Table(stmt.tableName(), schema);
        catalog.createTable(table);
        for (Map.Entry<String, String> e : constraintIndexes.entrySet()) {
            catalog.createIndex(e.getKey(), stmt.tableName(), e.getValue(), true);
        }
        log.info("Table '{}' created", stmt.tableName());
        return QueryResult.ddl();
    }

    private QueryResult executeDropTable(DropTableStmt stmt, Catalog catalog, Transaction txn, LockManager lockManager) {
        if (stmt.ifExists() && !tableExists(catalog, stmt.tableName())) {
            return QueryResult.ddl();
        }
        if (txn != null && lockManager != null) {
            lockManager.acquireExclusive(txn.getId(), LockResource.table(stmt.tableName()));
        }
        catalog.dropTable(stmt.tableName());
        log.info("Table '{}' dropped", stmt.tableName());
        return QueryResult.ddl();
    }

    private QueryResult executeAlterTable(AlterTableStmt stmt, Catalog catalog, Transaction txn, LockManager lockManager) {
        if (txn != null && lockManager != null) {
            lockManager.acquireExclusive(txn.getId(), LockResource.table(stmt.tableName()));
        }
        catalog.addColumn(stmt.tableName(), stmt.addColumn());
        return QueryResult.ddl();
    }

    private QueryResult executeCreateIndex(CreateIndexStmt stmt, Catalog catalog) {
        if (stmt.ifNotExists() && catalog.getIndex(stmt.indexName()).isPresent()) {
            return QueryResult.ddl();
        }
        catalog.createIndex(stmt.indexName(), stmt.tableName(), stmt.columnName(), stmt.unique());
        log.info("Index '{}' created on {}({})", stmt.indexName(), stmt.tableName(), stmt.columnName());
        return QueryResult.ddl();
    }

    private QueryResult executeDropIndex(DropIndexStmt stmt, Catalog catalog) {
        if (stmt.ifExists() && catalog.getIndex(stmt.indexName()).isEmpty()) {
            return QueryResult.ddl();
        }
        catalog.dropIndex(stmt.indexName());
        log.info("Index '{}' dropped", stmt.indexName());
        return QueryResult.ddl();
    }

    // =========================================================================
    // INSERT
    // =========================================================================

    private QueryResult executeInsert(InsertStmt stmt, Catalog catalog, Transaction txn, LockManager lockManager, TransactionManager txnManager) {
        if (txn != null && lockManager != null && txn.getIsolationLevel() == IsolationLevel.SERIALIZABLE) {
            lockManager.acquireExclusive(txn.getId(), LockResource.table(stmt.tableName()));
        }

        Table  table  = catalog.getTable(stmt.tableName());
        Schema schema = table.getSchema();
        List<ColumnDef> cols = schema.columns();

        // target position of each value: all columns in order, or the named columns
        int[] target;
        if (stmt.columns() == null) {
            target = new int[cols.size()];
            for (int i = 0; i < target.length; i++) target[i] = i;
        } else {
            target = new int[stmt.columns().size()];
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < target.length; i++) {
                String name = stmt.columns().get(i);
                int pos = -1;
                for (int c = 0; c < cols.size(); c++) {
                    if (cols.get(c).name().equalsIgnoreCase(name)) { pos = c; break; }
                }
                if (pos < 0) {
                    throw new SchemaException("Column '%s' does not exist in table '%s'".formatted(name, stmt.tableName()));
                }
                if (!seen.add(pos)) {
                    throw new SchemaException("Column '%s' is listed more than once".formatted(name));
                }
                target[i] = pos;
            }
        }

        // Validate and convert every row before inserting any, so a bad row inserts nothing
        List<Row> newRows = new ArrayList<>();
        for (List<LiteralExpr> values : stmt.rows()) {
            if (values.size() != target.length) {
                throw new SchemaException(
                        "INSERT into '%s' expected %d value(s) but got %d"
                                .formatted(stmt.tableName(), target.length, values.size()));
            }
            Object[] typed = new Object[cols.size()];          // unlisted columns are NULL
            for (int i = 0; i < values.size(); i++) {
                ColumnDef col = cols.get(target[i]);
                typed[target[i]] = coerce(values.get(i).value(), col.dataType(), col.name());
            }
            for (int c = 0; c < cols.size(); c++) {
                if (typed[c] == null && cols.get(c).notNull()) {
                    throw new SchemaException("NULL value in column '%s' of table '%s' violates NOT NULL constraint"
                            .formatted(cols.get(c).name(), stmt.tableName()));
                }
            }
            Map<String, Object> rowValues = new LinkedHashMap<>();
            for (int c = 0; c < cols.size(); c++) rowValues.put(cols.get(c).name(), typed[c]);
            newRows.add(new Row(rowValues));
        }

        for (Row newRow : newRows) {
            insertOne(stmt.tableName(), table, newRow, catalog, txn, txnManager);
        }
        log.debug("Inserted {} row(s) into '{}'", newRows.size(), stmt.tableName());
        return QueryResult.dml(newRows.size());
    }

    private void insertOne(String tableName, Table table, Row newRow, Catalog catalog, Transaction txn, TransactionManager txnManager) {
        if (table instanceof DiskTable diskTable) {
            TableFile tf = diskTable.getTableFile();
            List<dev.koradb.index.Index> indexes = catalog.getIndexesForTable(tableName);
            // Unique check BEFORE touching the heap, so a violation leaves nothing behind.
            // Locking the unique indexes makes check+insert atomic against concurrent writers.
            withUniqueIndexLocks(indexes, () -> {
                for (dev.koradb.index.Index idx : indexes) {
                    if (idx.isUnique()) {
                        checkUnique(idx, newRow.get(idx.getColumnName()), tf, null, txn, txnManager);
                    }
                }
                RecordId rid = tf.insertRow(newRow, txn);
                if (txn != null) {
                    txn.addUndoAction(new InsertUndoAction(tableName, newRow, rid));
                }
                for (dev.koradb.index.Index idx : indexes) {
                    Object val = newRow.get(idx.getColumnName());
                    if (val != null) {
                        idx.insertUnchecked(new dev.koradb.index.IndexKey(val, idx.getKeyType()), rid);
                    }
                }
            });
        } else {
            if (txn != null) {
                table.insertRow(newRow, txn);
                txn.addUndoAction(new InsertUndoAction(tableName, newRow));
            } else {
                table.insertRow(newRow);
            }
        }
    }

    // =========================================================================
    // SELECT
    // =========================================================================

    private QueryResult executeSelect(SelectStmt stmt, Catalog catalog, Transaction txn, LockManager lockManager, TransactionManager txnManager) {
        if (txn != null) {
            if (txn.getIsolationLevel() == IsolationLevel.SERIALIZABLE) {
                if (lockManager != null) {
                    if (stmt.tableName() != null) {
                        lockManager.acquireShared(txn.getId(), LockResource.table(stmt.tableName()));
                    }
                    for (JoinClause j : stmt.joins()) {
                        lockManager.acquireShared(txn.getId(), LockResource.table(j.tableName()));
                    }
                }
            }
            if (txnManager != null) {
                ReadView readView;
                if (txn.getIsolationLevel() == IsolationLevel.READ_COMMITTED) {
                    readView = txnManager.createReadView(txn);
                } else {
                    readView = txn.getOrCreateReadView(() -> txnManager.createReadView(txn));
                }
                if (readView != null) {
                    MvccContext.setReadView(readView);
                }
            }
        }

        try {
            // Validate projected columns exist if simple projection without JOIN
            if (stmt.tableName() != null && stmt.joins().isEmpty() && !stmt.isSelectStar()) {
                Schema schema = catalog.getTable(stmt.tableName()).getSchema();
                for (SelectItem item : stmt.selectItems()) {
                    if (!item.isAggregate() && !item.isComputed()) {
                        String col = item.expression();
                        if (!schema.hasColumn(col) && (!col.contains(".") || !schema.hasColumn(col.substring(col.indexOf('.') + 1)))) {
                            throw new SchemaException(
                                    "Column '%s' does not exist in table '%s'".formatted(col, stmt.tableName()));
                        }
                    }
                }
            }

            dev.koradb.planner.physical.PhysicalPlan compiled = queryPlanner.plan(stmt, catalog).physicalPlan();
            dev.koradb.execution.operator.Operator plan = compiled.rootOperator();
            plan.open();
            List<Row> rows = new ArrayList<>();
            try {
                Row r;
                while ((r = plan.next()) != null) {
                    rows.add(r);
                }
            } finally {
                plan.close();
            }

            List<String> outputCols = plan.getSchema().columns().stream().map(ColumnDef::name).toList();
            log.debug("SELECT returned {} row(s) from '{}' (plan cost: {})", rows.size(), stmt.tableName() == null ? "(no table)" : stmt.tableName(), compiled.cost().format());
            return QueryResult.select(rows, outputCols);
        } finally {
            MvccContext.clear();
        }
    }

    // =========================================================================
    // UPDATE
    // =========================================================================

    private QueryResult executeUpdate(UpdateStmt stmt, Catalog catalog, Transaction txn, LockManager lockManager, TransactionManager txnManager) {
        if (txn != null && lockManager != null && txn.getIsolationLevel() == IsolationLevel.SERIALIZABLE) {
            lockManager.acquireExclusive(txn.getId(), LockResource.table(stmt.tableName()));
        }

        if (txn != null && txnManager != null) {
            ReadView readView;
            if (txn.getIsolationLevel() == IsolationLevel.READ_COMMITTED) {
                readView = txnManager.createReadView(txn);
            } else {
                readView = txn.getOrCreateReadView(() -> txnManager.createReadView(txn));
            }
            if (readView != null) {
                MvccContext.setReadView(readView);
            }
        }

        try {
            Table  table     = catalog.getTable(stmt.tableName());
            Schema schema    = table.getSchema();
            var    evaluator = new ExpressionEvaluator(schema);

            if (stmt.where().isEmpty()) {
                log.warn("UPDATE '{}' has no WHERE clause — all rows will be updated",
                        stmt.tableName());
            }

            // Validate assignment columns
            for (UpdateStmt.Assignment assignment : stmt.assignments()) {
                if (!schema.hasColumn(assignment.columnName())) {
                    throw new SchemaException(
                            "Column '%s' does not exist in table '%s'"
                                    .formatted(assignment.columnName(), stmt.tableName()));
                }
            }

            int count = 0;

            if (table instanceof DiskTable diskTable) {
                // Disk path: scan with RIDs, delete + reinsert each matched row
                TableFile tf = diskTable.getTableFile();
                for (TableFile.RidRow rr : tf.scanWithRids()) {
                    if (matchesWhere(rr.row(), stmt.where(), evaluator)) {
                        Map<String, Object> newVals = assign(rr.row(), stmt, schema, evaluator);
                        Row newRow = new Row(newVals);
                        List<dev.koradb.index.Index> indexes = catalog.getIndexesForTable(stmt.tableName());
                        withUniqueIndexLocks(indexes, () -> {
                            for (dev.koradb.index.Index idx : indexes) {
                                Object oldVal = rr.row().get(idx.getColumnName());
                                Object newVal = newVals.get(idx.getColumnName());
                                if (idx.isUnique() && !java.util.Objects.equals(oldVal, newVal)) {
                                    checkUnique(idx, newVal, tf, rr.rid(), txn, txnManager);
                                }
                            }
                            RecordId newRid = tf.updateRow(rr.rid(), newRow, txn);
                            if (txn != null) {
                                txn.addUndoAction(new UpdateUndoAction(stmt.tableName(), rr.row(), newRow, rr.rid(), newRid, txn.getId().value()));
                            }
                            // MVCC: the new version lives at a new RID, so EVERY index needs an entry for it
                            // (even when the key is unchanged). Old entries stay: older snapshots and a
                            // rollback still need them; IndexScan re-checks visibility and the key.
                            for (dev.koradb.index.Index idx : indexes) {
                                Object newVal = newVals.get(idx.getColumnName());
                                if (newVal != null) {
                                    idx.insertUnchecked(new dev.koradb.index.IndexKey(newVal, idx.getKeyType()), newRid);
                                }
                            }
                        });
                        count++;
                    }
                }
            } else {
                if (txn != null) {
                    List<Row> visible = table.getRows();
                    for (Row row : visible) {
                        if (matchesWhere(row, stmt.where(), evaluator)) {
                            Map<String, Object> newVals = assign(row, stmt, schema, evaluator);
                            Row newRow = new Row(newVals);
                            table.updateRow(row, newRow, txn);
                            txn.addUndoAction(new UpdateUndoAction(stmt.tableName(), row, newRow));
                            count++;
                        }
                    }
                } else {
                    // In-memory path: mutate rows in-place
                    for (Row row : table.mutableRows()) {
                        if (matchesWhere(row, stmt.where(), evaluator)) {
                            Map<String, Object> newVals = assign(row, stmt, schema, evaluator);
                            for (UpdateStmt.Assignment a : stmt.assignments()) {
                                row.set(a.columnName(), newVals.get(a.columnName()));
                            }
                            count++;
                        }
                    }
                }
            }

            log.debug("UPDATE '{}' affected {} row(s)", stmt.tableName(), count);
            return QueryResult.dml(count);
        } finally {
            MvccContext.clear();
        }
    }

    // =========================================================================
    // DELETE
    // =========================================================================

    private QueryResult executeDelete(DeleteStmt stmt, Catalog catalog, Transaction txn, LockManager lockManager, TransactionManager txnManager) {
        if (txn != null && lockManager != null && txn.getIsolationLevel() == IsolationLevel.SERIALIZABLE) {
            lockManager.acquireExclusive(txn.getId(), LockResource.table(stmt.tableName()));
        }

        if (txn != null && txnManager != null) {
            ReadView readView;
            if (txn.getIsolationLevel() == IsolationLevel.READ_COMMITTED) {
                readView = txnManager.createReadView(txn);
            } else {
                readView = txn.getOrCreateReadView(() -> txnManager.createReadView(txn));
            }
            if (readView != null) {
                MvccContext.setReadView(readView);
            }
        }

        try {
            Table  table     = catalog.getTable(stmt.tableName());
            Schema schema    = table.getSchema();
            var    evaluator = new ExpressionEvaluator(schema);

            if (stmt.where().isEmpty()) {
                log.warn("DELETE FROM '{}' has no WHERE clause — all rows will be deleted",
                        stmt.tableName());
            }

            int count = 0;

            if (table instanceof DiskTable diskTable) {
                // Disk path: scan with RIDs, delete each matched slot
                TableFile tf = diskTable.getTableFile();
                for (TableFile.RidRow rr : tf.scanWithRids()) {
                    if (matchesWhere(rr.row(), stmt.where(), evaluator)) {
                        tf.deleteRow(rr.rid(), txn);
                        // Index entries are kept: the deleted version is still visible to older
                        // snapshots and comes back on ROLLBACK. IndexScan filters dead versions.
                        if (txn != null) {
                            txn.addUndoAction(new DeleteUndoAction(stmt.tableName(), rr.row(), rr.rid(), txn.getId().value()));
                        }
                        count++;
                    }
                }
            } else {
                if (txn != null) {
                    List<Row> visible = table.getRows();
                    for (Row row : visible) {
                        if (matchesWhere(row, stmt.where(), evaluator)) {
                            table.deleteRow(row, txn);
                            txn.addUndoAction(new DeleteUndoAction(stmt.tableName(), row));
                            count++;
                        }
                    }
                } else {
                    // In-memory path: reverse-index delete to avoid index shifting
                    List<Row> mutable = table.mutableRows();
                    int i = mutable.size() - 1;
                    while (i >= 0) {
                        Row row = mutable.get(i);
                        if (matchesWhere(row, stmt.where(), evaluator)) {
                            table.deleteRow(i);
                            count++;
                        }
                        i--;
                    }
                }
            }

            log.debug("DELETE FROM '{}' removed {} row(s)", stmt.tableName(), count);
            return QueryResult.dml(count);
        } finally {
            MvccContext.clear();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Returns true if the row satisfies all predicates in the WHERE clause.
     * If where is absent, all rows match (no filtering).
     */
    /**
     * New values of a row for UPDATE ... SET: every assignment is evaluated against the old row
     * ({@code SET a = b, b = a} swaps), then converted to the column type.
     */
    private Map<String, Object> assign(Row oldRow, UpdateStmt stmt, Schema schema, ExpressionEvaluator ev) {
        Map<String, Object> newVals = new LinkedHashMap<>(oldRow.values());
        for (UpdateStmt.Assignment a : stmt.assignments()) {
            ColumnDef col = schema.findColumn(a.columnName());
            Object value = coerce(ev.eval(a.value(), oldRow), col.dataType(), col.name());
            if (value == null && col.notNull()) {
                throw new SchemaException("NULL value in column '%s' of table '%s' violates NOT NULL constraint"
                        .formatted(col.name(), stmt.tableName()));
            }
            newVals.put(col.name(), value);
        }
        return newVals;
    }

    private boolean matchesWhere(Row row, Optional<WhereClause> where, ExpressionEvaluator ev) {
        if (where.isEmpty()) return true;
        return where.get().predicates().stream().allMatch(pred -> ev.evaluate(pred, row));
    }

    /**
     * Projects a row to only the specified columns.
     */
    private Row projectRow(Row source, List<String> columns) {
        Map<String, Object> projected = new LinkedHashMap<>();
        for (String col : columns) {
            projected.put(col, source.get(col));
        }
        return new Row(projected);
    }

    /**
     * Coerces a raw literal value to the target {@link DataType}.
     *
     * <p>This handles the case where the parser stores all numeric literals
     * as their most natural type (Integer, Long, Double) but the column might
     * need a specific type — e.g., inserting an {@code int} literal into a
     * {@code BIGINT} column.
     */
    private Object coerce(Object raw, DataType targetType, String colName) {
        if (raw == null) return null;

        return switch (targetType) {
            case INT -> switch (raw) {
                case Integer i -> i;
                case Long    l -> {
                    if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                        yield failRange(raw, targetType, colName);
                    }
                    yield l.intValue();
                }
                case Double  d -> {
                    if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
                        yield failRange(raw, targetType, colName);
                    }
                    yield d.intValue();
                }
                default        -> failCoerce(raw, targetType, colName);
            };
            case BIGINT -> switch (raw) {
                case Integer i -> i.longValue();
                case Long    l -> l;
                case Double  d -> {
                    if (d != Math.rint(d) || d < -9.223372036854775808E18 || d >= 9.223372036854775808E18) {
                        yield failRange(raw, targetType, colName);
                    }
                    yield d.longValue();
                }
                default        -> failCoerce(raw, targetType, colName);
            };
            case DOUBLE -> switch (raw) {
                case Integer i -> i.doubleValue();
                case Long    l -> l.doubleValue();
                case Double  d -> d;
                default        -> failCoerce(raw, targetType, colName);
            };
            case BOOLEAN -> switch (raw) {
                case Boolean b -> b;
                default        -> failCoerce(raw, targetType, colName);
            };
            case VARCHAR -> switch (raw) {
                case String s -> s;
                default       -> String.valueOf(raw); // lenient: coerce to string
            };
            case TIMESTAMP -> switch (raw) {
                case LocalDateTime ldt -> ldt;
                case String s          -> parseTimestamp(s, colName);
                default                -> failCoerce(raw, targetType, colName);
            };
        };
    }

    private LocalDateTime parseTimestamp(String s, String colName) {
        try {
            return TimestampParser.parse(s);
        } catch (DateTimeParseException e) {
            throw new ExecutionException(
                    ("Cannot parse '%s' as TIMESTAMP for column '%s'. " +
                    "Expected format: yyyy-MM-dd HH:mm:ss").formatted(s, colName));
        }
    }

    /** Value of the right kind but not representable exactly in the column type (M5: no silent truncation). */
    private Object failRange(Object value, DataType target, String colName) {
        throw new ExecutionException(
                "Value %s is out of range for column '%s' of type %s (it would be truncated)"
                        .formatted(value, colName, target));
    }

    private Object failCoerce(Object value, DataType target, String colName) {
        throw new ExecutionException(
                "Cannot assign value '%s' (type %s) to column '%s' of type %s"
                        .formatted(value, value.getClass().getSimpleName(), colName, target));
    }

    // =========================================================================
    // Unique-index helpers (MVCC aware)
    // =========================================================================

    private static void withUniqueIndexLocks(List<dev.koradb.index.Index> indexes, Runnable body) {
        List<dev.koradb.index.Index> uniques = indexes.stream().filter(dev.koradb.index.Index::isUnique).toList();
        lockAndRun(uniques, 0, body);
    }

    private static void lockAndRun(List<dev.koradb.index.Index> locks, int i, Runnable body) {
        if (i == locks.size()) { body.run(); return; }
        synchronized (locks.get(i)) { lockAndRun(locks, i + 1, body); }
    }

    /**
     * Throws if another LIVE row version already holds {@code value} in a unique index.
     * Index entries pointing at dead versions (deleted/updated by a committed txn, or written by an
     * aborted txn) are not duplicates. {@code selfRid} is the version being replaced by an UPDATE.
     */
    private static void checkUnique(dev.koradb.index.Index idx, Object value, TableFile tf, RecordId selfRid,
                                    Transaction txn, TransactionManager txnManager) {
        if (value == null) return;
        dev.koradb.index.IndexKey key = new dev.koradb.index.IndexKey(value, idx.getKeyType());
        long me = txn != null ? txn.getId().value() : 0L;
        for (RecordId rid : idx.searchAll(key)) {
            if (rid.equals(selfRid)) continue;
            Row r = tf.readRawRow(rid);
            if (r == null || !java.util.Objects.equals(r.get(idx.getColumnName()), value)) continue;
            boolean xminAborted = r.getXmin() != 0L && txnManager != null && txnManager.isAborted(r.getXmin());
            if (xminAborted) continue;
            long xmax = r.getXmax();
            boolean deleted = xmax != 0L
                    && (xmax == me || (txnManager != null && txnManager.isCommitted(xmax)));
            if (!deleted) {
                throw new dev.koradb.exception.StorageException("Unique index violation: duplicate key '%s' in index '%s'"
                        .formatted(value, idx.getName()));
            }
        }
    }


    private QueryResult executeShow(ShowStmt stmt, Catalog catalog) {
        List<Row> rows = new ArrayList<>();
        switch (stmt.kind()) {
            case DATABASES -> {
                for (String name : catalog.listDatabases()) {
                    rows.add(new Row(new java.util.LinkedHashMap<>(Map.of("database", name))));
                }
                return new QueryResult(rows, 0, List.of("database"));
            }
            case TABLES -> {
                for (Table t : catalog.requireCurrentDatabase().listTables()) {
                    rows.add(new Row(new java.util.LinkedHashMap<>(Map.of("table_name", t.getName()))));
                }
                return new QueryResult(rows, 0, List.of("table_name"));
            }
            default -> {
                Table t = catalog.getTable(stmt.tableName());
                for (ColumnDef c : t.getSchema().columns()) {
                    java.util.LinkedHashMap<String, Object> v = new java.util.LinkedHashMap<>();
                    v.put("column_name", c.name());
                    v.put("data_type", c.dataType().name());
                    rows.add(new Row(v));
                }
                return new QueryResult(rows, 0, List.of("column_name", "data_type"));
            }
        }
    }

}
