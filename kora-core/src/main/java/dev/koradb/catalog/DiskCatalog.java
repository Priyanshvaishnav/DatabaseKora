package dev.koradb.catalog;

import dev.koradb.exception.SchemaException;
import dev.koradb.exception.StorageException;
import dev.koradb.sql.ast.ColumnDef;
import dev.koradb.sql.ast.DataType;
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

    private String currentDbName = null; // lowercase key

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
        closeOpenFiles();
        try {
            deleteDirectory(dbDir);
        } catch (IOException e) {
            throw new StorageException("Cannot delete database: " + name, e);
        }
        if (name.equalsIgnoreCase(currentDbName)) {
            currentDbName = null;
        }
        log.info("Dropped database '{}'", name);
    }

    @Override
    public Database getDatabase(String name) {
        Path dbDir = dbDir(name);
        requireDbDir(name, dbDir);
        // Reconstruct a Database object from disk
        Database db = new Database(name);
        loadMeta(dbDir).forEach(db::addTable);
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
        closeOpenFiles();
        currentDbName = name.toLowerCase();
        // Pre-open TableFile handles for all existing tables
        loadMeta(dbDir).forEach(table -> {
            Path filePath = StorageConfig.tableFilePath(dbDir, table.getName());
            openFiles.put(table.getName().toLowerCase(),
                          new TableFile(table.getSchema(), filePath));
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
        TableFile tf       = new TableFile(table.getSchema(), filePath);
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
        // Return a DiskTable proxy that delegates to the open TableFile
        TableFile tf = openFiles.get(key);
        return new DiskTable(tableName, tf);
    }

    @Override
    public List<Table> listTables() {
        requireCurrentDbDir();
        return openFiles.entrySet().stream()
                .map(e -> (Table) new DiskTable(e.getKey(), e.getValue()))
                .toList();
    }

    // =========================================================================
    // catalog.meta persistence
    // =========================================================================

    private void appendToMeta(Path dbDir, Table table) {
        String line = tableToMetaLine(table) + "\n";
        try {
            Files.writeString(metaFile(dbDir),
                    Files.readString(metaFile(dbDir), StandardCharsets.UTF_8) + line,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("Cannot write catalog.meta in " + dbDir, e);
        }
    }

    private void rewriteMeta(Path dbDir) {
        // Rebuild catalog.meta from currently open files
        Path metaPath = metaFile(dbDir);
        try {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, TableFile> entry : openFiles.entrySet()) {
                // We need the schema — read it from the existing meta
            }
            // Re-read existing lines, removing the dropped table
            String existing = Files.exists(metaPath)
                    ? Files.readString(metaPath, StandardCharsets.UTF_8) : "";
            List<String> kept = Arrays.stream(existing.split("\n"))
                    .filter(l -> !l.isBlank())
                    .filter(l -> {
                        // Keep lines for tables that are still open
                        String[] parts = l.split("\\s+");
                        if (parts.length < 2) return false;
                        return openFiles.containsKey(parts[1].toLowerCase());
                    })
                    .toList();
            Files.writeString(metaPath, String.join("\n", kept) + (kept.isEmpty() ? "" : "\n"),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("Cannot rewrite catalog.meta in " + dbDir, e);
        }
    }

    /** Loads all table schemas from catalog.meta (returns empty list if file is empty). */
    private List<Table> loadMeta(Path dbDir) {
        Path metaPath = metaFile(dbDir);
        if (!Files.exists(metaPath)) return List.of();

        try {
            return Files.readAllLines(metaPath, StandardCharsets.UTF_8).stream()
                    .filter(l -> !l.isBlank())
                    .map(this::parseMetaLine)
                    .toList();
        } catch (IOException e) {
            throw new StorageException("Cannot read catalog.meta in " + dbDir, e);
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
        closeOpenFiles();
        log.debug("DiskCatalog closed");
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
