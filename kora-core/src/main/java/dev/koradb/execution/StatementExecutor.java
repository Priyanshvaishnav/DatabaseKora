package dev.koradb.execution;

import dev.koradb.catalog.*;
import dev.koradb.exception.ExecutionException;
import dev.koradb.exception.SchemaException;
import dev.koradb.sql.ast.*;
import dev.koradb.storage.TableFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * The main execution engine for Milestone 1.
 *
 * <p>Walks the {@link Statement} AST and performs operations against the
 * {@link Catalog}. Each statement type is handled in its own private method.
 *
 * <h2>Design</h2>
 * <p>Using an exhaustive {@code switch} on the sealed {@link Statement} interface
 * ensures the compiler catches any new statement type added to the AST that
 * is not yet handled here.
 *
 * <h2>Thread safety</h2>
 * <p>Not thread-safe in M1. Concurrency support will be added in Phase 7.
 */
public final class StatementExecutor {

    private static final Logger log = LoggerFactory.getLogger(StatementExecutor.class);

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Executes a parsed SQL statement against the given catalog.
     *
     * @param statement the parsed AST node to execute
     * @param catalog   the schema registry and row store
     * @return the result of execution
     */
    public QueryResult execute(Statement statement, Catalog catalog) {
        log.debug("Executing: {}", statement.getClass().getSimpleName());

        return switch (statement) {
            case CreateDatabaseStmt s -> executeCreateDatabase(s, catalog);
            case DropDatabaseStmt   s -> executeDropDatabase(s, catalog);
            case UseDatabaseStmt    s -> executeUseDatabase(s, catalog);
            case CreateTableStmt    s -> executeCreateTable(s, catalog);
            case DropTableStmt      s -> executeDropTable(s, catalog);
            case CreateIndexStmt    s -> executeCreateIndex(s, catalog);
            case DropIndexStmt      s -> executeDropIndex(s, catalog);
            case InsertStmt         s -> executeInsert(s, catalog);
            case SelectStmt         s -> executeSelect(s, catalog);
            case UpdateStmt         s -> executeUpdate(s, catalog);
            case DeleteStmt         s -> executeDelete(s, catalog);
        };
    }

    // =========================================================================
    // DDL
    // =========================================================================

    private QueryResult executeCreateDatabase(CreateDatabaseStmt stmt, Catalog catalog) {
        catalog.createDatabase(stmt.databaseName());
        log.info("Database '{}' created", stmt.databaseName());
        return QueryResult.ddl();
    }

    private QueryResult executeDropDatabase(DropDatabaseStmt stmt, Catalog catalog) {
        catalog.dropDatabase(stmt.databaseName());
        log.info("Database '{}' dropped", stmt.databaseName());
        return QueryResult.ddl();
    }

    private QueryResult executeUseDatabase(UseDatabaseStmt stmt, Catalog catalog) {
        catalog.useDatabase(stmt.databaseName());
        log.info("Using database '{}'", stmt.databaseName());
        return QueryResult.ddl();
    }

    private QueryResult executeCreateTable(CreateTableStmt stmt, Catalog catalog) {
        Schema schema = new Schema(stmt.columnDefs());
        Table  table  = new Table(stmt.tableName(), schema);
        catalog.createTable(table);
        log.info("Table '{}' created", stmt.tableName());
        return QueryResult.ddl();
    }

    private QueryResult executeDropTable(DropTableStmt stmt, Catalog catalog) {
        catalog.dropTable(stmt.tableName());
        log.info("Table '{}' dropped", stmt.tableName());
        return QueryResult.ddl();
    }

    private QueryResult executeCreateIndex(CreateIndexStmt stmt, Catalog catalog) {
        catalog.createIndex(stmt.indexName(), stmt.tableName(), stmt.columnName(), stmt.unique());
        log.info("Index '{}' created on {}({})", stmt.indexName(), stmt.tableName(), stmt.columnName());
        return QueryResult.ddl();
    }

    private QueryResult executeDropIndex(DropIndexStmt stmt, Catalog catalog) {
        catalog.dropIndex(stmt.indexName());
        log.info("Index '{}' dropped", stmt.indexName());
        return QueryResult.ddl();
    }

    // =========================================================================
    // INSERT
    // =========================================================================

    private QueryResult executeInsert(InsertStmt stmt, Catalog catalog) {
        Table  table  = catalog.getTable(stmt.tableName());
        Schema schema = table.getSchema();

        List<LiteralExpr> values = stmt.values();
        List<ColumnDef>   cols   = schema.columns();

        if (values.size() != cols.size()) {
            throw new SchemaException(
                    "INSERT into '%s' expected %d value(s) but got %d"
                            .formatted(stmt.tableName(), cols.size(), values.size()));
        }

        Map<String, Object> rowValues = new LinkedHashMap<>();
        for (int i = 0; i < cols.size(); i++) {
            ColumnDef col  = cols.get(i);
            Object    raw  = values.get(i).value();
            Object    typed = coerce(raw, col.dataType(), col.name());
            rowValues.put(col.name(), typed);
        }

        table.insertRow(new Row(rowValues));
        log.debug("Inserted 1 row into '{}'", stmt.tableName());
        return QueryResult.dml(1);
    }

    // =========================================================================
    // SELECT
    // =========================================================================

    private QueryResult executeSelect(SelectStmt stmt, Catalog catalog) {
        Table  table  = catalog.getTable(stmt.tableName());
        Schema schema = table.getSchema();

        // Validate projected columns exist if simple projection without JOIN
        if (stmt.join().isEmpty() && !stmt.isSelectStar()) {
            for (SelectItem item : stmt.selectItems()) {
                if (!item.isAggregate()) {
                    String col = item.expression();
                    if (!schema.hasColumn(col) && (!col.contains(".") || !schema.hasColumn(col.substring(col.indexOf('.') + 1)))) {
                        throw new SchemaException(
                                "Column '%s' does not exist in table '%s'".formatted(col, stmt.tableName()));
                    }
                }
            }
        }

        dev.koradb.execution.operator.Operator plan =
                dev.koradb.execution.operator.PhysicalPlanBuilder.build(stmt, catalog);
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
        log.debug("SELECT returned {} row(s) from '{}'", rows.size(), stmt.tableName());
        return QueryResult.select(rows, outputCols);
    }

    // =========================================================================
    // UPDATE
    // =========================================================================

    private QueryResult executeUpdate(UpdateStmt stmt, Catalog catalog) {
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
                    Map<String, Object> newVals = new LinkedHashMap<>(rr.row().values());
                    for (UpdateStmt.Assignment a : stmt.assignments()) {
                        ColumnDef col   = schema.findColumn(a.columnName());
                        Object    typed = coerce(a.value().value(), col.dataType(), col.name());
                        newVals.put(a.columnName(), typed);
                    }
                    tf.updateRow(rr.rid(), new Row(newVals));
                    for (dev.koradb.index.Index idx : catalog.getIndexesForTable(stmt.tableName())) {
                        Object oldVal = rr.row().get(idx.getColumnName());
                        Object newVal = newVals.get(idx.getColumnName());
                        if (!java.util.Objects.equals(oldVal, newVal)) {
                            if (oldVal != null) {
                                idx.delete(new dev.koradb.index.IndexKey(oldVal, idx.getKeyType()), rr.rid());
                            }
                            if (newVal != null) {
                                idx.insert(new dev.koradb.index.IndexKey(newVal, idx.getKeyType()), rr.rid());
                            }
                        }
                    }
                    count++;
                }
            }
        } else {
            // In-memory path: mutate rows in-place
            for (Row row : table.mutableRows()) {
                if (matchesWhere(row, stmt.where(), evaluator)) {
                    for (UpdateStmt.Assignment a : stmt.assignments()) {
                        ColumnDef col   = schema.findColumn(a.columnName());
                        Object    typed = coerce(a.value().value(), col.dataType(), col.name());
                        row.set(a.columnName(), typed);
                    }
                    count++;
                }
            }
        }

        log.debug("UPDATE '{}' affected {} row(s)", stmt.tableName(), count);
        return QueryResult.dml(count);
    }

    // =========================================================================
    // DELETE
    // =========================================================================

    private QueryResult executeDelete(DeleteStmt stmt, Catalog catalog) {
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
                    tf.deleteRow(rr.rid());
                    for (dev.koradb.index.Index idx : catalog.getIndexesForTable(stmt.tableName())) {
                        Object val = rr.row().get(idx.getColumnName());
                        if (val != null) {
                            idx.delete(new dev.koradb.index.IndexKey(val, idx.getKeyType()), rr.rid());
                        }
                    }
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

        log.debug("DELETE FROM '{}' removed {} row(s)", stmt.tableName(), count);
        return QueryResult.dml(count);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Returns true if the row satisfies all predicates in the WHERE clause.
     * If where is absent, all rows match (no filtering).
     */
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
                case Long    l -> l.intValue();
                case Double  d -> d.intValue();
                default        -> failCoerce(raw, targetType, colName);
            };
            case BIGINT -> switch (raw) {
                case Integer i -> i.longValue();
                case Long    l -> l;
                case Double  d -> d.longValue();
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
            return LocalDateTime.parse(s, TIMESTAMP_FORMAT);
        } catch (DateTimeParseException e) {
            throw new ExecutionException(
                    "Cannot parse '%s' as TIMESTAMP for column '%s'. " +
                    "Expected format: yyyy-MM-dd HH:mm:ss".formatted(s, colName));
        }
    }

    private Object failCoerce(Object value, DataType target, String colName) {
        throw new ExecutionException(
                "Cannot assign value '%s' (type %s) to column '%s' of type %s"
                        .formatted(value, value.getClass().getSimpleName(), colName, target));
    }
}
