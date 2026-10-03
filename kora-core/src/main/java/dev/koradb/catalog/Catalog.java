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
     * Returns the table with the given name from the current database.
     *
     * @throws dev.koradb.exception.SchemaException if not found or no DB selected
     */
    Table getTable(String tableName);

    /** Returns all tables in the current database. */
    List<Table> listTables();
}
