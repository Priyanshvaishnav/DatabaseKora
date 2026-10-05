package dev.koradb.catalog;

import dev.koradb.buffer.BufferPool;
import dev.koradb.exception.SchemaException;
import dev.koradb.exception.StorageException;
import dev.koradb.sql.ast.ColumnDef;
import dev.koradb.sql.ast.DataType;
import dev.koradb.storage.DiskPageManager;
import dev.koradb.storage.StorageConfig;
import dev.koradb.storage.TableFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Disk-backed implementation of {@link Catalog}.
 *
 * <h2>Directory structure</h2>
 * <pre>
 * dataDir/
 *   mydb/
 *     catalog.meta      ← table schemas (plain text, one TABLE line per table)
 *     users.kdb         ← table data pages
 *     orders.kdb
 *   otherdb/
 *     catalog.meta
 *     ...
 * </pre>
 *
 * <h2>catalog.meta format</h2>
 * <pre>
 * TABLE users id:INT name:VARCHAR age:INT active:BOOLEAN salary:DOUBLE
 * TABLE orders id:INT user_id:INT total:DOUBLE
 * </pre>
 *
 * <p>One line per table. Each column is {@code name:TYPE}. Human-readable
 * and inspectable with any text editor.
 *
 * <h2>Session state</h2>
 * <p>The current database is tracked in memory (not persisted). It is
 * re-established via {@link #useDatabase} after every restart.
 *
 * <h2>Thread safety</h2>
 * <p>Not thread-safe in M2. Per-connection sessions will be introduced in M11.
 */
public final class DiskCatalog implements Catalog, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DiskCatalog.class);

    private final Path dataDir;

    /** Cache of open TableFile handles — one per table in the current database. */
    private final Map<String, TableFile> openFiles = new LinkedHashMap<>();

    /** Cache of open B+ Tree index handles — one per index in the current database. */
    private final Map<String, dev.koradb.index.BPlusTree> openIndexes = new LinkedHashMap<>();

    private String currentDbName = null; // lowercase key
    private dev.koradb.storage.wal.LogManager logManager = null;

    /**
     * Creates a DiskCatalog rooted at the given data directory.
     * The directory is created if it does not exist.
     *
     * @param dataDir root data directory (e.g., {@code Path.of("data")})
     */
    public DiskCatalog(Path dataDir) {
        this.dataDir = dataDir;
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new StorageException("Cannot create data directory: " + dataDir, e);
        }
        log.debug("DiskCatalog initialised at: {}", dataDir);
    }

    // =========================================================================
    // Database management
    // =========================================================================

    @Override
    public void createDatabase(String name) {
        Path dbDir = dbDir(name);
        if (Files.exists(dbDir)) {
            throw new SchemaException("Database '%s' already exists".formatted(name));
        }
        try {
            Files.createDirectories(dbDir);
            // Create empty catalog.meta
            Files.writeString(metaFile(dbDir), "", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("Cannot create database directory: " + dbDir, e);
        }
        log.info("Created database '{}'", name);
    }

    @Override
    public void dropDatabase(String name) {
        Path dbDir = dbDir(name);
        requireDbDir(name, dbDir);
        if (name.equalsIgnoreCase(currentDbName)) {
            closeOpenIndexes();
            closeOpenFiles();
            if (logManager != null) {
                logManager.close();
                logManager = null;
            }
            currentDbName = null;
        } else {
            closeOpenFiles();
        }
        try {
            deleteDirectory(dbDir);
        } catch (IOException e) {
            throw new StorageException("Cannot delete database: " + name, e);
        }
        log.info("Dropped database '{}'", name);
    }

    @Override
    public Database getDatabase(String name) {
        Path dbDir = dbDir(name);
        requireDbDir(name, dbDir);
        // Reconstruct a Database object from disk
        Database db = new Database(name);
        loadTablesMeta(dbDir).forEach(db::addTable);
        return db;
    }

    @Override
    public List<String> listDatabases() {
        try {
            if (!Files.exists(dataDir)) return List.of();
            return Files.list(dataDir)
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new StorageException("Cannot list databases", e);
        }
    }

    // =========================================================================
    // Session state
    // =========================================================================

    @Override
    public void useDatabase(String name) {
        Path dbDir = dbDir(name);
        requireDbDir(name, dbDir);
        closeOpenIndexes();
        closeOpenFiles();
        if (logManager != null) {
            logManager.close();
            logManager = null;
        }
        currentDbName = name.toLowerCase();
        Path walPath = StorageConfig.walFilePath(dbDir);
        this.logManager = new dev.koradb.storage.wal.LogManager(walPath);
        // Pre-open TableFile handles for all existing tables
        loadTablesMeta(dbDir).forEach(table -> {
            Path filePath = StorageConfig.tableFilePath(dbDir, table.getName());
            BufferPool bp = new BufferPool(new DiskPageManager(filePath), logManager);
            openFiles.put(table.getName().toLowerCase(),
                          new TableFile(table.getSchema(), filePath, bp));
        });
        // Pre-open BPlusTree handles for all existing indexes
        loadIndexesMeta(dbDir).forEach(meta -> {
            Path idxPath = dbDir.resolve(meta.name().toLowerCase() + ".idx");
            TableFile tf = openFiles.get(meta.tableName().toLowerCase());
            if (tf != null && tf.getSchema().findColumn(meta.columnName()) != null) {
                DataType keyType = tf.getSchema().findColumn(meta.columnName()).dataType();
                DiskPageManager dpm = new DiskPageManager(idxPath);
                BufferPool bp = new BufferPool(dpm);
                openIndexes.put(meta.name().toLowerCase(),
                        new dev.koradb.index.BPlusTree(meta.name(), meta.tableName(), meta.columnName(), keyType, meta.unique(), bp));
            }
        });
        log.info("Using database '{}'", name);
    }

    @Override
    public Optional<String> currentDatabase() {
        return Optional.ofNullable(currentDbName);
    }

    @Override
    public Database requireCurrentDatabase() {
        if (currentDbName == null) {
            throw new SchemaException("No database selected. Use 'USE <database>' first.");
        }
        return getDatabase(currentDbName);
    }

    // =========================================================================
    // Table management
    // =========================================================================

    @Override
    public void createTable(Table table) {
        Path dbDir = requireCurrentDbDir();
        if (openFiles.containsKey(table.getName().toLowerCase())) {
            throw new SchemaException(
                    "Table '%s' already exists in database '%s'"
                            .formatted(table.getName(), currentDbName));
        }
        // Create .kdb file and open TableFile
        Path      filePath = StorageConfig.tableFilePath(dbDir, table.getName());
        BufferPool bp       = new BufferPool(new DiskPageManager(filePath), logManager);
        TableFile tf       = new TableFile(table.getSchema(), filePath, bp);
        openFiles.put(table.getName().toLowerCase(), tf);

        // Persist the schema to catalog.meta
        appendToMeta(dbDir, table);
        log.info("Created table '{}' in database '{}'", table.getName(), currentDbName);
    }

    @Override
    public void dropTable(String tableName) {
        requireCurrentDbDir();
        String key = tableName.toLowerCase();
        if (!openFiles.containsKey(key)) {
            throw new SchemaException(
                    "Table '%s' does not exist in database '%s'"
                            .formatted(tableName, currentDbName));
        }

        // Drop any indexes belonging to this table
        List<String> indexesToDrop = openIndexes.values().stream()
                .filter(idx -> idx.getTableName().equalsIgnoreCase(tableName))
                .map(dev.koradb.index.BPlusTree::getName)
                .toList();
        for (String idxName : indexesToDrop) {
            dropIndex(idxName);
        }

        // Close and remove the TableFile
        TableFile tf = openFiles.remove(key);
        tf.close();

        // Delete the .kdb file
        Path filePath = StorageConfig.tableFilePath(dbDir(currentDbName), tableName);
        try { Files.deleteIfExists(filePath); } catch (IOException e) {
            log.warn("Could not delete table file: {}", filePath, e);
        }

        // Rewrite catalog.meta without this table
        rewriteMeta(dbDir(currentDbName));
        log.info("Dropped table '{}' from database '{}'", tableName, currentDbName);
    }

    @Override
    public Table getTable(String tableName) {
        requireCurrentDbDir();
        String key = tableName.toLowerCase();
        if (!openFiles.containsKey(key)) {
            throw new SchemaException(
                    "Table '%s' does not exist in database '%s'"
                            .formatted(tableName, currentDbName));
        }
        // Return a DiskTable proxy that delegates to the open TableFile and updates indexes
        TableFile tf = openFiles.get(key);
        return new DiskTable(tableName, tf, () -> getIndexesForTable(tableName));
    }

    @Override
    public List<Table> listTables() {
        requireCurrentDbDir();
        return openFiles.entrySet().stream()
                .map(e -> (Table) new DiskTable(e.getKey(), e.getValue(), () -> getIndexesForTable(e.getKey())))
                .toList();
    }

    // =========================================================================
    // Index management
    // =========================================================================

    @Override
    public void createIndex(String indexName, String tableName, String columnName, boolean unique) {
        Path dbDir = requireCurrentDbDir();
        String tableKey = tableName.toLowerCase();
        if (!openFiles.containsKey(tableKey)) {
            throw new SchemaException("Table '%s' does not exist in database '%s'".formatted(tableName, currentDbName));
        }
        if (openIndexes.containsKey(indexName.toLowerCase())) {
            throw new SchemaException("Index '%s' already exists in database '%s'".formatted(indexName, currentDbName));
        }

        TableFile tf = openFiles.get(tableKey);
        if (!tf.getSchema().hasColumn(columnName)) {
            throw new SchemaException("Column '%s' does not exist in table '%s'".formatted(columnName, tableName));
        }

        DataType colType = tf.getSchema().findColumn(columnName).dataType();
        Path indexPath = dbDir.resolve(indexName.toLowerCase() + ".idx");
        DiskPageManager dpm = new DiskPageManager(indexPath);
        BufferPool bp = new BufferPool(dpm);
        dev.koradb.index.BPlusTree bTree = new dev.koradb.index.BPlusTree(
                indexName, tableName, columnName, colType, unique, bp);

        // Populate with existing rows
        for (TableFile.RidRow rr : tf.scanWithRids()) {
            Object val = rr.row().get(columnName);
            if (val != null) {
                bTree.insert(new dev.koradb.index.IndexKey(val, colType), rr.rid());
            }
        }
        bTree.flush();

        openIndexes.put(indexName.toLowerCase(), bTree);
        appendIndexToMeta(dbDir, indexName, tableName, columnName, unique);
        log.info("Created index '{}' on {}({}) in database '{}'", indexName, tableName, columnName, currentDbName);
    }

    @Override
    public void dropIndex(String indexName) {
        Path dbDir = requireCurrentDbDir();
        String key = indexName.toLowerCase();
        dev.koradb.index.BPlusTree bTree = openIndexes.remove(key);
        if (bTree == null) {
            throw new SchemaException("Index '%s' does not exist in database '%s'".formatted(indexName, currentDbName));
        }
        bTree.close();
        Path indexPath = dbDir.resolve(key + ".idx");
        try {
            Files.deleteIfExists(indexPath);
        } catch (IOException e) {
            log.warn("Could not delete index file: {}", indexPath, e);
        }
        rewriteMeta(dbDir);
        log.info("Dropped index '{}' from database '{}'", indexName, currentDbName);
    }

    @Override
    public Optional<dev.koradb.index.Index> getIndex(String indexName) {
        return Optional.ofNullable(openIndexes.get(indexName.toLowerCase()));
    }

    @Override
    public Optional<dev.koradb.index.Index> getIndexForColumn(String tableName, String columnName) {
        return openIndexes.values().stream()
                .filter(idx -> idx.getTableName().equalsIgnoreCase(tableName)
                        && idx.getColumnName().equalsIgnoreCase(columnName))
                .map(idx -> (dev.koradb.index.Index) idx)
                .findFirst();
    }

    @Override
    public List<dev.koradb.index.Index> getIndexesForTable(String tableName) {
        return openIndexes.values().stream()
                .filter(idx -> idx.getTableName().equalsIgnoreCase(tableName))
                .map(idx -> (dev.koradb.index.Index) idx)
                .toList();
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public TableStatistics getTableStatistics(String tableName) {
        Table table = getTable(tableName);
        List<Row> rows = table.getRows();
        long rowCount = rows.size();
        int pageCount = 1;
        if (table instanceof DiskTable dt) {
            pageCount = Math.max(1, dt.getTableFile().pageCount());
        }

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

    // =========================================================================
    // catalog.meta persistence
    // =========================================================================

    private void appendToMeta(Path dbDir, Table table) {
        String line = tableToMetaLine(table) + "\n";
        try {
            Files.writeString(metaFile(dbDir), line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new StorageException("Cannot write catalog.meta in " + dbDir, e);
        }
    }

    private void appendIndexToMeta(Path dbDir, String indexName, String tableName, String columnName, boolean unique) {
        String line = "INDEX " + indexName + " " + tableName + " " + columnName + " " + unique + "\n";
        try {
            Files.writeString(metaFile(dbDir), line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new StorageException("Cannot write index to catalog.meta in " + dbDir, e);
        }
    }

    private void rewriteMeta(Path dbDir) {
        Path metaPath = metaFile(dbDir);
        try {
            String existing = Files.exists(metaPath)
                    ? Files.readString(metaPath, StandardCharsets.UTF_8) : "";
            List<String> kept = Arrays.stream(existing.split("\n"))
                    .map(String::trim)
                    .filter(l -> !l.isBlank())
                    .filter(l -> {
                        String[] parts = l.split("\\s+");
                        if (parts.length < 2) return false;
                        if (parts[0].equalsIgnoreCase("TABLE")) {
                            return openFiles.containsKey(parts[1].toLowerCase());
                        } else if (parts[0].equalsIgnoreCase("INDEX")) {
                            return openIndexes.containsKey(parts[1].toLowerCase());
                        }
                        return false;
                    })
                    .toList();
            Files.writeString(metaPath, String.join("\n", kept) + (kept.isEmpty() ? "" : "\n"),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("Cannot rewrite catalog.meta in " + dbDir, e);
        }
    }

    private record IndexMeta(String name, String tableName, String columnName, boolean unique) {}

    /** Loads all table schemas from catalog.meta. */
    private List<Table> loadTablesMeta(Path dbDir) {
        Path metaPath = metaFile(dbDir);
        if (!Files.exists(metaPath)) return List.of();

        try {
            return Files.readAllLines(metaPath, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(l -> !l.isBlank() && l.toUpperCase().startsWith("TABLE "))
                    .map(this::parseMetaLine)
                    .toList();
        } catch (IOException e) {
            throw new StorageException("Cannot read catalog.meta in " + dbDir, e);
        }
    }

    /** Loads all index definitions from catalog.meta. */
    private List<IndexMeta> loadIndexesMeta(Path dbDir) {
        Path metaPath = metaFile(dbDir);
        if (!Files.exists(metaPath)) return List.of();

        try {
            return Files.readAllLines(metaPath, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(l -> !l.isBlank() && l.toUpperCase().startsWith("INDEX "))
                    .map(l -> {
                        String[] parts = l.split("\\s+");
                        return new IndexMeta(parts[1], parts[2], parts[3], Boolean.parseBoolean(parts[4]));
                    })
                    .toList();
        } catch (IOException e) {
            throw new StorageException("Cannot read index metadata from catalog.meta in " + dbDir, e);
        }
    }

    /**
     * Parses a line like:
     * {@code TABLE users id:INT name:VARCHAR age:INT active:BOOLEAN salary:DOUBLE}
     */
    private Table parseMetaLine(String line) {
        String[] tokens = line.trim().split("\\s+");
        if (tokens.length < 2 || !tokens[0].equalsIgnoreCase("TABLE")) {
            throw new StorageException("Invalid catalog.meta line: " + line);
        }
        String tableName = tokens[1];
        List<ColumnDef> cols = new ArrayList<>();
        for (int i = 2; i < tokens.length; i++) {
            String[] parts = tokens[i].split(":");
            if (parts.length != 2) {
                throw new StorageException("Invalid column spec '%s' in: %s".formatted(tokens[i], line));
            }
            cols.add(new ColumnDef(parts[0], DataType.valueOf(parts[1].toUpperCase())));
        }
        return new Table(tableName, new Schema(cols));
    }

    private String tableToMetaLine(Table table) {
        String cols = table.getSchema().columns().stream()
                .map(c -> c.name() + ":" + c.dataType().name())
                .collect(Collectors.joining(" "));
        return "TABLE " + table.getName() + " " + cols;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public void close() {
        closeOpenIndexes();
        closeOpenFiles();
        if (logManager != null) {
            logManager.close();
            logManager = null;
        }
        log.debug("DiskCatalog closed");
    }

    @Override
    public dev.koradb.storage.wal.LogManager getLogManager() {
        return logManager;
    }

    public Collection<TableFile> getOpenTableFiles() {
        return Collections.unmodifiableCollection(openFiles.values());
    }

    private void closeOpenIndexes() {
        openIndexes.values().forEach(dev.koradb.index.BPlusTree::close);
        openIndexes.clear();
    }

    private void closeOpenFiles() {
        openFiles.values().forEach(TableFile::close);
        openFiles.clear();
    }

    // =========================================================================
    // Private path helpers
    // =========================================================================

    private Path dbDir(String dbName) {
        return dataDir.resolve(dbName.toLowerCase());
    }

    private Path metaFile(Path dbDir) {
        return dbDir.resolve(StorageConfig.CATALOG_META_FILE);
    }

    private Path requireCurrentDbDir() {
        if (currentDbName == null) {
            throw new SchemaException("No database selected. Use 'USE <database>' first.");
        }
        return dbDir(currentDbName);
    }

    private void requireDbDir(String name, Path dbDir) {
        if (!Files.exists(dbDir) || !Files.isDirectory(dbDir)) {
            throw new SchemaException("Database '%s' does not exist".formatted(name));
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); }
                catch (IOException e) { throw new StorageException("Cannot delete: " + p, e); }
            });
        }
    }
}
