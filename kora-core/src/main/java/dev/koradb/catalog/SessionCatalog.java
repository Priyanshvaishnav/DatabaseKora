package dev.koradb.catalog;

import java.util.List;
import java.util.Optional;

/**
 * A per-session view of a shared {@link DiskCatalog}.
 *
 * <p>Holds the session's own current database; every operation is forwarded to the shared catalog
 * with that database name. {@code USE} in one session therefore never changes the database of
 * another session, and never closes files or WALs another session is using.
 */
public final class SessionCatalog implements Catalog {

    private final DiskCatalog shared;
    private volatile String currentDb;   // lowercase, null = none selected

    public SessionCatalog(DiskCatalog shared) {
        this(shared, null);
    }

    /** @param initialDb database selected when the session starts (may be null) */
    public SessionCatalog(DiskCatalog shared, String initialDb) {
        this.shared = java.util.Objects.requireNonNull(shared, "shared catalog must not be null");
        this.currentDb = initialDb == null ? null : initialDb.toLowerCase();
    }

    public DiskCatalog getSharedCatalog() {
        return shared;
    }

    @Override public void createDatabase(String name)   { shared.createDatabase(name); }

    @Override
    public void dropDatabase(String name) {
        shared.dropDatabase(name);
        if (name.equalsIgnoreCase(currentDb)) currentDb = null;
    }

    @Override public Database getDatabase(String name)  { return shared.getDatabase(name); }
    @Override public List<String> listDatabases()       { return shared.listDatabases(); }

    @Override
    public void useDatabase(String name) {
        shared.openDatabase(name);          // opens + recovers once per process; never closes others
        currentDb = name.toLowerCase();
    }

    @Override public Optional<String> currentDatabase() { return Optional.ofNullable(currentDb); }

    @Override
    public Database requireCurrentDatabase() {
        if (currentDb == null) {
            throw new dev.koradb.exception.SchemaException("No database selected. Use 'USE <database>' first.");
        }
        return shared.getDatabase(currentDb);
    }

    @Override public void createTable(Table table)       { shared.createTableIn(currentDb, table); }
    @Override public void dropTable(String tableName)     { shared.dropTableIn(currentDb, tableName); }
    @Override public Table getTable(String tableName)     { return shared.getTableIn(currentDb, tableName); }
    @Override public void addColumn(String tableName, dev.koradb.sql.ast.ColumnDef column) { shared.addColumnIn(currentDb, tableName, column); }
    @Override public List<Table> listTables()             { return shared.listTablesIn(currentDb); }

    @Override
    public void createIndex(String indexName, String tableName, String columnName, boolean unique) {
        shared.createIndexIn(currentDb, indexName, tableName, columnName, unique);
    }

    @Override public void dropIndex(String indexName)     { shared.dropIndexIn(currentDb, indexName); }

    @Override
    public Optional<dev.koradb.index.Index> getIndex(String indexName) {
        return shared.getIndexIn(currentDb, indexName);
    }

    @Override
    public Optional<dev.koradb.index.Index> getIndexForColumn(String tableName, String columnName) {
        return shared.getIndexForColumnIn(currentDb, tableName, columnName);
    }

    @Override
    public List<dev.koradb.index.Index> getIndexesForTable(String tableName) {
        return shared.getIndexesForTableIn(currentDb, tableName);
    }

    @Override
    public TableStatistics getTableStatistics(String tableName) {
        return shared.getTableStatisticsIn(currentDb, tableName);
    }

    @Override
    public dev.koradb.storage.wal.LogManager getLogManager() {
        return shared.getLogManagerOf(currentDb);
    }
}
