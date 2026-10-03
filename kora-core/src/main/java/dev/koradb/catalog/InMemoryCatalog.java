package dev.koradb.catalog;

import dev.koradb.exception.SchemaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory implementation of {@link Catalog}.
 *
 * <p>All state is held in Java {@link LinkedHashMap}s. This implementation is
 * intentionally simple — it will be replaced by a disk-backed catalog in
 * Milestone 2 without changing any of the executor code (they depend only
 * on the {@link Catalog} interface).
 *
 * <p>Not thread-safe in Milestone 1. Concurrency support is added in Phase 7.
 */
public final class InMemoryCatalog implements Catalog {

    private static final Logger log = LoggerFactory.getLogger(InMemoryCatalog.class);

    private final Map<String, Database> databases = new LinkedHashMap<>();
    private String currentDbName = null;

    // =========================================================================
    // Database management
    // =========================================================================

    @Override
    public void createDatabase(String name) {
        String key = name.toLowerCase();
        if (databases.containsKey(key)) {
            throw new SchemaException("Database '%s' already exists".formatted(name));
        }
        databases.put(key, new Database(name));
        log.debug("Created database '{}'", name);
    }

    @Override
    public void dropDatabase(String name) {
        String key = name.toLowerCase();
        if (!databases.containsKey(key)) {
            throw new SchemaException("Database '%s' does not exist".formatted(name));
        }
        databases.remove(key);
        if (key.equals(currentDbName)) {
            currentDbName = null;
        }
        log.debug("Dropped database '{}'", name);
    }

    @Override
    public Database getDatabase(String name) {
        Database db = databases.get(name.toLowerCase());
        if (db == null) {
            throw new SchemaException("Database '%s' does not exist".formatted(name));
        }
        return db;
    }

    @Override
    public List<String> listDatabases() {
        return databases.values().stream().map(Database::getName).toList();
    }

    // =========================================================================
    // Session state
    // =========================================================================

    @Override
    public void useDatabase(String name) {
        String key = name.toLowerCase();
        if (!databases.containsKey(key)) {
            throw new SchemaException("Database '%s' does not exist".formatted(name));
        }
        currentDbName = key;
        log.debug("Using database '{}'", name);
    }

    @Override
    public Optional<String> currentDatabase() {
        return Optional.ofNullable(currentDbName)
                .map(k -> databases.get(k).getName());
    }

    @Override
    public Database requireCurrentDatabase() {
        if (currentDbName == null) {
            throw new SchemaException("No database selected. Use 'USE <database>' first.");
        }
        return databases.get(currentDbName);
    }

    // =========================================================================
    // Table management
    // =========================================================================

    @Override
    public void createTable(Table table) {
        Database db = requireCurrentDatabase();
        if (db.hasTable(table.getName())) {
            throw new SchemaException(
                    "Table '%s' already exists in database '%s'"
                            .formatted(table.getName(), db.getName()));
        }
        db.addTable(table);
        log.debug("Created table '{}' in database '{}'", table.getName(), db.getName());
    }

    @Override
    public void dropTable(String tableName) {
        Database db = requireCurrentDatabase();
        if (!db.hasTable(tableName)) {
            throw new SchemaException(
                    "Table '%s' does not exist in database '%s'"
                            .formatted(tableName, db.getName()));
        }
        db.removeTable(tableName);
        log.debug("Dropped table '{}' from database '{}'", tableName, db.getName());
    }

    @Override
    public Table getTable(String tableName) {
        Database db = requireCurrentDatabase();
        Table table = db.getTable(tableName);
        if (table == null) {
            throw new SchemaException(
                    "Table '%s' does not exist in database '%s'"
                            .formatted(tableName, db.getName()));
        }
        return table;
    }

    @Override
    public List<Table> listTables() {
        return requireCurrentDatabase().listTables();
    }
}
