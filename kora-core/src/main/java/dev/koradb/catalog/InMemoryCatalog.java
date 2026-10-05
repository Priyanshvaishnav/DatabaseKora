package dev.koradb.catalog;

import dev.koradb.exception.SchemaException;
import dev.koradb.sql.ast.ColumnDef;
import dev.koradb.sql.ast.DataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

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

    // =========================================================================
    // Index management
    // =========================================================================

    private final Map<String, dev.koradb.index.Index> indexes = new LinkedHashMap<>();

    @Override
    public void createIndex(String indexName, String tableName, String columnName, boolean unique) {
        Table table = getTable(tableName);
        if (!table.getSchema().hasColumn(columnName)) {
            throw new SchemaException("Column '%s' does not exist in table '%s'".formatted(columnName, tableName));
        }
        if (indexes.containsKey(indexName.toLowerCase())) {
            throw new SchemaException("Index '%s' already exists".formatted(indexName));
        }

        DataType colType = table.getSchema().findColumn(columnName).dataType();
        InMemoryIndex idx = new InMemoryIndex(indexName, tableName, columnName, colType, unique);

        // Populate with existing rows
        List<Row> rows = table.getRows();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            Object val = r.get(columnName);
            if (val != null) {
                idx.insert(new dev.koradb.index.IndexKey(val, colType), new dev.koradb.storage.RecordId(0, i));
            }
        }

        indexes.put(indexName.toLowerCase(), idx);
        log.debug("Created in-memory index '{}' on {}({})", indexName, tableName, columnName);
    }

    @Override
    public void dropIndex(String indexName) {
        if (indexes.remove(indexName.toLowerCase()) == null) {
            throw new SchemaException("Index '%s' does not exist".formatted(indexName));
        }
        log.debug("Dropped in-memory index '{}'", indexName);
    }

    @Override
    public Optional<dev.koradb.index.Index> getIndex(String indexName) {
        return Optional.ofNullable(indexes.get(indexName.toLowerCase()));
    }

    @Override
    public Optional<dev.koradb.index.Index> getIndexForColumn(String tableName, String columnName) {
        return indexes.values().stream()
                .filter(idx -> idx.getTableName().equalsIgnoreCase(tableName)
                        && idx.getColumnName().equalsIgnoreCase(columnName))
                .findFirst();
    }

    @Override
    public List<dev.koradb.index.Index> getIndexesForTable(String tableName) {
        return indexes.values().stream()
                .filter(idx -> idx.getTableName().equalsIgnoreCase(tableName))
                .toList();
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public TableStatistics getTableStatistics(String tableName) {
        Table table = getTable(tableName);
        List<Row> rows = table.getRows();
        long rowCount = rows.size();
        int pageCount = Math.max(1, (int) Math.ceil(rowCount / 50.0));

        Map<String, ColumnStatistics> colStats = new HashMap<>();
        for (ColumnDef col : table.getSchema().columns()) {
            Set<Object> distinctValues = new HashSet<>();
            long nulls = 0;
            Comparable min = null;
            Comparable max = null;

            for (Row r : rows) {
                Object val = r.get(col.name());
                if (val == null) {
                    nulls++;
                } else {
                    distinctValues.add(val);
                    if (val instanceof Comparable c) {
                        if (min == null || c.compareTo(min) < 0) min = c;
                        if (max == null || c.compareTo(max) > 0) max = c;
                    }
                }
            }
            colStats.put(col.name(), new ColumnStatistics(
                    col.name(),
                    Math.max(1, distinctValues.size()),
                    nulls,
                    min,
                    max
            ));
        }

        return new TableStatistics(tableName, rowCount, pageCount, colStats);
    }

    private static class InMemoryIndex implements dev.koradb.index.Index {
        private final String name;
        private final String tableName;
        private final String columnName;
        private final DataType keyType;
        private final boolean unique;
        private final java.util.TreeMap<dev.koradb.index.IndexKey, List<dev.koradb.storage.RecordId>> map = new java.util.TreeMap<>();

        public InMemoryIndex(String name, String tableName, String columnName, DataType keyType, boolean unique) {
            this.name = name;
            this.tableName = tableName;
            this.columnName = columnName;
            this.keyType = keyType;
            this.unique = unique;
        }

        @Override public String getName() { return name; }
        @Override public String getTableName() { return tableName; }
        @Override public String getColumnName() { return columnName; }
        @Override public DataType getKeyType() { return keyType; }
        @Override public boolean isUnique() { return unique; }

        @Override
        public void insert(dev.koradb.index.IndexKey key, dev.koradb.storage.RecordId rid) {
            if (unique && map.containsKey(key) && !map.get(key).isEmpty()) {
                throw new dev.koradb.exception.StorageException("Unique index violation: duplicate key " + key);
            }
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(rid);
        }

        @Override
        public Optional<dev.koradb.storage.RecordId> search(dev.koradb.index.IndexKey key) {
            List<dev.koradb.storage.RecordId> rids = map.get(key);
            if (rids != null && !rids.isEmpty()) {
                return Optional.of(rids.get(0));
            }
            return Optional.empty();
        }

        @Override
        public List<dev.koradb.storage.RecordId> searchAll(dev.koradb.index.IndexKey key) {
            return map.getOrDefault(key, List.of());
        }

        @Override
        public List<dev.koradb.storage.RecordId> rangeScan(dev.koradb.index.IndexKey low, dev.koradb.index.IndexKey high, boolean lowInc, boolean highInc) {
            List<dev.koradb.storage.RecordId> res = new ArrayList<>();
            for (var entry : map.entrySet()) {
                dev.koradb.index.IndexKey k = entry.getKey();
                if (low != null) {
                    int c = k.compareTo(low);
                    if (c < 0 || (!lowInc && c == 0)) continue;
                }
                if (high != null) {
                    int c = k.compareTo(high);
                    if (c > 0 || (!highInc && c == 0)) break;
                }
                res.addAll(entry.getValue());
            }
            return res;
        }

        @Override
        public boolean delete(dev.koradb.index.IndexKey key, dev.koradb.storage.RecordId rid) {
            List<dev.koradb.storage.RecordId> rids = map.get(key);
            if (rids != null) {
                boolean rm = rid == null ? (rids.remove(0) != null) : rids.remove(rid);
                if (rids.isEmpty()) map.remove(key);
                return rm;
            }
            return false;
        }

        @Override public void flush() {}
        @Override public void close() {}
    }
}
