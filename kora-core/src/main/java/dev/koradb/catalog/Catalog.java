package dev.koradb.catalog;

import java.util.List;
import java.util.Optional;

/**
 * The schema registry for KoraDB.
 *
 * <p>The Catalog manages the lifecycle of databases and tables.
 * In Milestone 1 it is backed entirely by in-memory collections.
 * In Milestone 2 it will be backed by disk.
 *
 * <p>Session state ({@link #currentDatabase()}) is also held here.
 * In the server phase each connection will have its own session state.
 */
public interface Catalog {

    // -------------------------------------------------------------------------
    // Database management
    // -------------------------------------------------------------------------

    /**
     * Creates a new database with the given name.
     *
     * @throws dev.koradb.exception.SchemaException if a database with this name already exists
     */
    void createDatabase(String name);

    /**
     * Drops the database with the given name and all its tables.
     *
     * @throws dev.koradb.exception.SchemaException if the database does not exist
     */
    void dropDatabase(String name);

    /**
     * Returns the database with the given name.
     *
     * @throws dev.koradb.exception.SchemaException if not found
     */
    Database getDatabase(String name);

    /** Returns all known database names. */
    List<String> listDatabases();

    // -------------------------------------------------------------------------
    // Session state
    // -------------------------------------------------------------------------

    /**
     * Sets the current (active) database for this session.
     *
     * @throws dev.koradb.exception.SchemaException if the database does not exist
     */
    void useDatabase(String name);

    /**
     * Returns the name of the currently active database, or empty if none is set.
     */
    Optional<String> currentDatabase();

    /**
     * Returns the currently active database.
     *
     * @throws dev.koradb.exception.SchemaException if no database has been selected
     */
    Database requireCurrentDatabase();

    // -------------------------------------------------------------------------
    // Table management — operate on the currently active database
    // -------------------------------------------------------------------------

    /**
     * Creates a table in the current database.
     *
     * @throws dev.koradb.exception.SchemaException if the table already exists or no DB selected
     */
    void createTable(Table table);

    /**
     * Drops a table from the current database.
     *
     * @throws dev.koradb.exception.SchemaException if the table does not exist or no DB selected
     */
    void dropTable(String tableName);

    /**
     * Adds a column to a table of the current database (ALTER TABLE ADD COLUMN). Existing rows read
     * NULL for it, so a NOT NULL column can only be added to an empty table.
     */
    default void addColumn(String tableName, dev.koradb.sql.ast.ColumnDef column) {
        throw new UnsupportedOperationException("ALTER TABLE is not supported by " + getClass().getSimpleName());
    }

    /**
     * Returns the table with the given name from the current database.
     *
     * @throws dev.koradb.exception.SchemaException if not found or no DB selected
     */
    Table getTable(String tableName);

    /** Returns all tables in the current database. */
    List<Table> listTables();

    // -------------------------------------------------------------------------
    // Index management
    // -------------------------------------------------------------------------

    /**
     * Creates an index on the given table and column.
     *
     * @throws dev.koradb.exception.SchemaException if the index already exists or table/column not found
     */
    void createIndex(String indexName, String tableName, String columnName, boolean unique);

    /**
     * Drops an index by name from the current database.
     *
     * @throws dev.koradb.exception.SchemaException if the index does not exist
     */
    void dropIndex(String indexName);

    /**
     * Returns the index with the given name, or empty if not found.
     */
    java.util.Optional<dev.koradb.index.Index> getIndex(String indexName);

    /**
     * Returns the index defined on the given table and column, or empty if none exists.
     */
    java.util.Optional<dev.koradb.index.Index> getIndexForColumn(String tableName, String columnName);

    /**
     * Returns all indexes for a given table.
     */
    List<dev.koradb.index.Index> getIndexesForTable(String tableName);

    /**
     * Returns table statistics (row count, page count, NDV per column) for query planning.
     */
    TableStatistics getTableStatistics(String tableName);

    /**
     * Returns the Write-Ahead Log (WAL) manager for the active database, or null if none.
     */
    default dev.koradb.storage.wal.LogManager getLogManager() {
        return null;
    }
}
