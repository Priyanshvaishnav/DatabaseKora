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
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Disk-backed implementation of {@link Catalog}.
 *
 * <h2>Directory structure</h2>
 * <pre>
 * dataDir/
 *   _txnid.hwm          ← transaction-id high-water mark (ids are never reused across restarts)
 *   mydb/
 *     catalog.meta      ← table schemas (plain text, one TABLE line per table)
 *     kora.wal          ← this database's write-ahead log
 *     users.kdb         ← table data pages
 *     idx_users_id.idx  ← B+ tree index pages
 * </pre>
 *
 * <h2>catalog.meta format</h2>
 * <pre>
 * TABLE users id:INT name:VARCHAR age:INT active:BOOLEAN salary:DOUBLE
 * INDEX idx_users_id users id false
 * </pre>
 *
 * <h2>Databases and sessions</h2>
 * <p>The catalog is shared by every session of a {@link dev.koradb.KoraDB} instance. Each database
 * is opened lazily on first use (running crash recovery exactly once per process) and then stays
 * open: its table files, indexes and WAL are never closed by another session's {@code USE}.
 * Which database a statement runs against is per session: the catalog itself keeps a "default"
 * current database for embedded use, and {@link #sessionView()} returns an independent
 * {@link Catalog} view with its own current database for each client connection.
 *
 * <h2>Thread safety</h2>
 * <p>Opening, creating and dropping databases, tables and indexes is serialized on this object.
 * Row-level concurrency is handled by {@link TableFile} (per-table latch), the buffer pool and
 * the B+ tree (both synchronized).
 */
public final class DiskCatalog implements Catalog, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DiskCatalog.class);
    private static final String TXN_HWM_FILE = "_txnid.hwm";
    private static final long TXN_ID_RESERVATION = 1000L;

    /** Runtime state of one open database. */
    private static final class OpenDb {
        final String name;                                   // lowercase key
        final Path dir;
        final dev.koradb.storage.wal.LogManager logManager;
        final Map<String, TableFile> files = new ConcurrentHashMap<>();
        final Map<String, dev.koradb.index.BPlusTree> indexes = new ConcurrentHashMap<>();

        OpenDb(String name, Path dir, dev.koradb.storage.wal.LogManager logManager) {
            this.name = name;
            this.dir = dir;
            this.logManager = logManager;
        }
    }

    private final Path dataDir;
    private final Map<String, OpenDb> openDbs = new ConcurrentHashMap<>();
    /** Current database of the default (embedded) view; client sessions use their own view. */
    private volatile String currentDbName = null; // lowercase key
    private dev.koradb.transaction.TransactionManager transactionManager = null;
    private volatile boolean suppressWalLogging = false;

    public void setSuppressWalLogging(boolean suppress) {
        this.suppressWalLogging = suppress;
    }

    public boolean isSuppressWalLogging() {
        return suppressWalLogging;
    }

    public synchronized void setTransactionManager(dev.koradb.transaction.TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
        initTxnIdHighWaterMark();
        syncTransactionManager();
    }

    /** Raises the next transaction id above every id stored in the open table files. */
    public void syncTransactionManager() {
        if (transactionManager == null) return;
        long max = 0L;
        for (OpenDb db : openDbs.values()) {
            for (TableFile tf : db.files.values()) {
                max = Math.max(max, tf.getMaxTxnId());
            }
        }
        if (max > 0) {
            transactionManager.initNextTxnId(max + 100);
        }
    }

    public DiskCatalog(Path dataDir) {
        this.dataDir = dataDir;
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new StorageException("Cannot create data directory: " + dataDir, e);
        }
        log.debug("DiskCatalog initialised at: {}", dataDir);
    }

    /**
     * A per-session view of this catalog with its own current database. The new session starts in
     * the default view's current database (if any); a later USE in either one is independent.
     */
    public Catalog sessionView() {
        return new SessionCatalog(this, currentDbName);
    }

    // =========================================================================
    // Transaction-id high-water mark
    // =========================================================================

    /**
     * Transaction ids are global to the KoraDB instance but stored per database. To guarantee an id
     * is never reused across restarts (whatever database is opened first), ids are reserved in blocks
     * and the end of the reserved block is persisted before any id in it is handed out.
     */
    private void initTxnIdHighWaterMark() {
        if (transactionManager == null) return;
        Path hwm = dataDir.resolve(TXN_HWM_FILE);
        long start = 0L;
        try {
            if (Files.exists(hwm)) {
                start = Long.parseLong(Files.readString(hwm, StandardCharsets.UTF_8).trim());
            } else {
                // First start with this data dir (or upgraded from an older version): scan once.
                start = scanMaxTxnIdOfAllDatabases() + 100;
            }
        } catch (IOException | NumberFormatException e) {
            log.warn("Cannot read {} ({}); scanning databases for the highest transaction id", hwm, e.getMessage());
            start = scanMaxTxnIdOfAllDatabases() + 100;
        }
        if (start > 0) {
            transactionManager.initNextTxnId(start);
        }
        transactionManager.setTxnIdReserver(this::persistTxnIdReservation, TXN_ID_RESERVATION);
    }

    private void persistTxnIdReservation(long reservedUpTo) {
        Path hwm = dataDir.resolve(TXN_HWM_FILE);
        Path tmp = dataDir.resolve(TXN_HWM_FILE + ".tmp");
        try {
            Files.writeString(tmp, Long.toString(reservedUpTo), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            Files.move(tmp, hwm, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new StorageException("Cannot persist transaction id reservation in " + hwm, e);
        }
    }

    private long scanMaxTxnIdOfAllDatabases() {
        long max = 0L;
        for (String db : listDatabases()) {
            Path dir = dbDir(db);
            for (Table t : loadTablesMeta(dir)) {
                Path fp = StorageConfig.tableFilePath(dir, t.getName());
                if (!Files.exists(fp)) continue;
                try (TableFile tf = new TableFile(t.getSchema(), fp)) {
                    max = Math.max(max, tf.getMaxTxnId());
                } catch (Exception e) {
                    log.warn("Cannot scan {} for transaction ids: {}", fp, e.getMessage());
                }
            }
        }
        return max;
    }

    // =========================================================================
    // Database management
    // =========================================================================

    @Override
    public synchronized void createDatabase(String name) {
        Path dbDir = dbDir(name);
        if (Files.exists(dbDir)) {
            throw new SchemaException("Database '%s' already exists".formatted(name));
        }
        try {
            Files.createDirectories(dbDir);
            Files.writeString(metaFile(dbDir), "", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("Cannot create database directory: " + dbDir, e);
        }
        log.info("Created database '{}'", name);
    }

    @Override
    public synchronized void dropDatabase(String name) {
        Path dbDir = dbDir(name);
        requireDbDir(name, dbDir);
        OpenDb db = openDbs.remove(name.toLowerCase());
        if (db != null) {
            closeDb(db);
        }
        if (name.equalsIgnoreCase(currentDbName)) {
            currentDbName = null;
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
        Database db = new Database(name);
        loadTablesMeta(dbDir).forEach(db::addTable);
        return db;
    }

    @Override
    public List<String> listDatabases() {
        try {
            if (!Files.exists(dataDir)) return List.of();
            try (var stream = Files.list(dataDir)) {
                return stream
                        .filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .toList();
            }
        } catch (IOException e) {
            throw new StorageException("Cannot list databases", e);
        }
    }

    // =========================================================================
    // Session state (default view)
    // =========================================================================

    /**
     * Selects the default view's current database, opening it if needed. Unlike earlier versions this
     * never closes anything: other sessions may be using other (or the same) databases concurrently.
     */
    @Override
    public void useDatabase(String name) {
        openDatabase(name);
        currentDbName = name.toLowerCase();
        log.debug("Using database '{}'", name);
    }

    @Override
    public Optional<String> currentDatabase() {
        return Optional.ofNullable(currentDbName);
    }

    @Override
    public Database requireCurrentDatabase() {
        return getDatabase(requireName(currentDbName));
    }

    /**
     * Opens a database (once per process): opens its WAL and table files, runs ARIES crash recovery
     * if the WAL needs it, and loads its indexes (rebuilding them when recovery changed pages).
     */
    synchronized void openDatabase(String name) {
        String key = name.toLowerCase();
        if (openDbs.containsKey(key)) return;
        Path dbDir = dbDir(name);
        requireDbDir(name, dbDir);

        Path walPath = StorageConfig.walFilePath(dbDir);
        OpenDb db = new OpenDb(key, dbDir, new dev.koradb.storage.wal.LogManager(walPath));
        loadTablesMeta(dbDir).forEach(table -> {
            Path filePath = StorageConfig.tableFilePath(dbDir, table.getName());
            BufferPool bp = new BufferPool(new DiskPageManager(filePath), db.logManager);
            db.files.put(table.getName().toLowerCase(), new TableFile(table.getSchema(), filePath, bp));
        });

        boolean recovered = false;
        long walMaxTxnId = 0L;
        dev.koradb.storage.recovery.RecoveryManager recoveryManager =
                new dev.koradb.storage.recovery.RecoveryManager(walPath, db.logManager, tn -> resolveFile(db, tn), () -> withToast(db.files.values()));
        if (recoveryManager.needsRecovery()) {
            dev.koradb.storage.recovery.RecoveryResult recoveryResult = recoveryManager.recover();
            if (recoveryResult.hasRecoveredChanges()) {
                log.info("Recovered database '{}': {}", name, recoveryResult);
                recovered = true;
            }
            if (transactionManager != null && recoveryResult.getAnalysisResult() != null) {
                for (long loser : recoveryResult.getAnalysisResult().getLoserTxnIds()) {
                    transactionManager.recordAborted(loser);
                }
                for (long winner : recoveryResult.getAnalysisResult().getWinnerTxnIds()) {
                    transactionManager.recordCommitted(winner);
                }
                walMaxTxnId = recoveryResult.getAnalysisResult().getTransactionTable().keySet().stream()
                        .mapToLong(Long::longValue).max().orElse(0L);
            }
        }

        long max = walMaxTxnId;
        for (TableFile tf : db.files.values()) {
            max = Math.max(max, tf.getMaxTxnId());
        }
        if (transactionManager != null && max > 0) {
            transactionManager.updateNextTxnId(max + 100);
        }

        final boolean rebuildIndexes = recovered;
        loadIndexesMeta(dbDir).forEach(meta -> {
            Path idxPath = dbDir.resolve(meta.name().toLowerCase() + ".idx");
            TableFile tf = db.files.get(meta.tableName().toLowerCase());
            if (tf != null && tf.getSchema().findColumn(meta.columnName()) != null) {
                DataType keyType = tf.getSchema().findColumn(meta.columnName()).dataType();
                if (rebuildIndexes) {
                    try { Files.deleteIfExists(idxPath); } catch (IOException ignored) {}
                }
                BufferPool bp = new BufferPool(new DiskPageManager(idxPath));
                dev.koradb.index.BPlusTree bTree = new dev.koradb.index.BPlusTree(
                        meta.name(), meta.tableName(), meta.columnName(), keyType, meta.unique(), bp);
                if (rebuildIndexes) {
                    // Index every stored version: entries are hints, IndexScan re-checks visibility.
                    for (TableFile.RidRow rr : tf.scanAllVersions()) {
                        Object val = rr.row().get(meta.columnName());
                        if (val != null) {
                            bTree.insertUnchecked(new dev.koradb.index.IndexKey(val, keyType), rr.rid());
                        }
                    }
                    bTree.flush();
                }
                db.indexes.put(meta.name().toLowerCase(), bTree);
            }
        });
        openDbs.put(key, db);
        log.info("Opened database '{}'", name);
    }

    private OpenDb open(String dbName) {
        String key = requireName(dbName);
        OpenDb db = openDbs.get(key);
        if (db == null) {
            openDatabase(key);
            db = openDbs.get(key);
        }
        return db;
    }

    // =========================================================================
    // Table management (explicit database; used by the default view and by SessionCatalog)
    // =========================================================================

    synchronized void createTableIn(String dbName, Table table) {
        OpenDb db = open(dbName);
        if (db.files.containsKey(table.getName().toLowerCase())) {
            throw new SchemaException(
                    "Table '%s' already exists in database '%s'".formatted(table.getName(), db.name));
        }
        Path filePath = StorageConfig.tableFilePath(db.dir, table.getName());
        BufferPool bp = new BufferPool(new DiskPageManager(filePath), db.logManager);
        TableFile tf = new TableFile(table.getSchema(), filePath, bp);
        db.files.put(table.getName().toLowerCase(), tf);
        appendToMeta(db.dir, table);

        if (db.logManager != null && !suppressWalLogging) {
            StringBuilder sb = new StringBuilder("CREATE TABLE ").append(table.getName()).append(" (");
            List<ColumnDef> cols = table.getSchema().columns();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(cols.get(i).name()).append(" ").append(cols.get(i).dataType());
            }
            sb.append(")");
            long lsn = db.logManager.appendDdl(0L, 0L, sb.toString());
            db.logManager.flush(lsn);
        }
        log.info("Created table '{}' in database '{}'", table.getName(), db.name);
    }

    synchronized void dropTableIn(String dbName, String tableName) {
        OpenDb db = open(dbName);
        String key = tableName.toLowerCase();
        if (!db.files.containsKey(key)) {
            throw new SchemaException("Table '%s' does not exist in database '%s'".formatted(tableName, db.name));
        }
        List<String> indexesToDrop = db.indexes.values().stream()
                .filter(idx -> idx.getTableName().equalsIgnoreCase(tableName))
                .map(dev.koradb.index.BPlusTree::getName)
                .toList();
        for (String idxName : indexesToDrop) {
            dropIndexIn(dbName, idxName);
        }
        TableFile tf = db.files.remove(key);
        tf.close();
        Path filePath = StorageConfig.tableFilePath(db.dir, tableName);
        try { Files.deleteIfExists(filePath); } catch (IOException e) {
            log.warn("Could not delete table file: {}", filePath, e);
        }
        Path toastPath = TableFile.toastPathFor(filePath);
        try { Files.deleteIfExists(toastPath); } catch (IOException e) {
            log.warn("Could not delete overflow file: {}", toastPath, e);
        }
        rewriteMeta(db);
        if (db.logManager != null && !suppressWalLogging) {
            long lsn = db.logManager.appendDdl(0L, 0L, "DROP TABLE " + tableName);
            db.logManager.flush(lsn);
        }
        log.info("Dropped table '{}' from database '{}'", tableName, db.name);
    }

    Table getTableIn(String dbName, String tableName) {
        OpenDb db = open(dbName);
        TableFile tf = db.files.get(tableName.toLowerCase());
        if (tf == null) {
            throw new SchemaException("Table '%s' does not exist in database '%s'".formatted(tableName, db.name));
        }
        return new DiskTable(tableName, tf, () -> getIndexesForTableIn(dbName, tableName));
    }

    List<Table> listTablesIn(String dbName) {
        OpenDb db = open(dbName);
        return db.files.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> (Table) new DiskTable(e.getKey(), e.getValue(), () -> getIndexesForTableIn(dbName, e.getKey())))
                .toList();
    }

    // =========================================================================
    // Index management
    // =========================================================================

    synchronized void createIndexIn(String dbName, String indexName, String tableName, String columnName, boolean unique) {
        OpenDb db = open(dbName);
        String tableKey = tableName.toLowerCase();
        if (!db.files.containsKey(tableKey)) {
            throw new SchemaException("Table '%s' does not exist in database '%s'".formatted(tableName, db.name));
        }
        if (db.indexes.containsKey(indexName.toLowerCase())) {
            throw new SchemaException("Index '%s' already exists in database '%s'".formatted(indexName, db.name));
        }
        TableFile tf = db.files.get(tableKey);
        if (!tf.getSchema().hasColumn(columnName)) {
            throw new SchemaException("Column '%s' does not exist in table '%s'".formatted(columnName, tableName));
        }
        DataType colType = tf.getSchema().findColumn(columnName).dataType();
        Path indexPath = db.dir.resolve(indexName.toLowerCase() + ".idx");
        BufferPool bp = new BufferPool(new DiskPageManager(indexPath));
        dev.koradb.index.BPlusTree bTree = new dev.koradb.index.BPlusTree(
                indexName, tableName, columnName, colType, unique, bp);
        try {
            // Unique check over the rows visible now ...
            if (unique) {
                Set<Object> seen = new HashSet<>();
                for (TableFile.RidRow rr : tf.scanWithRids()) {
                    Object val = rr.row().get(columnName);
                    if (val != null && !seen.add(val)) {
                        throw new StorageException("Unique index violation: duplicate key " + val + " in index " + indexName);
                    }
                }
            }
            // ... but index every stored version: older snapshots may still read them, and
            // IndexScan re-checks visibility and the key for every entry.
            for (TableFile.RidRow rr : tf.scanAllVersions()) {
                Object val = rr.row().get(columnName);
                if (val != null) {
                    bTree.insertUnchecked(new dev.koradb.index.IndexKey(val, colType), rr.rid());
                }
            }
        } catch (RuntimeException e) {
            bTree.close();
            try { Files.deleteIfExists(indexPath); } catch (IOException ignored) {}
            throw e;
        }
        bTree.flush();
        db.indexes.put(indexName.toLowerCase(), bTree);
        appendIndexToMeta(db.dir, indexName, tableName, columnName, unique);
        log.info("Created index '{}' on {}({}) in database '{}'", indexName, tableName, columnName, db.name);
    }

    synchronized void dropIndexIn(String dbName, String indexName) {
        OpenDb db = open(dbName);
        String key = indexName.toLowerCase();
        dev.koradb.index.BPlusTree bTree = db.indexes.remove(key);
        if (bTree == null) {
            throw new SchemaException("Index '%s' does not exist in database '%s'".formatted(indexName, db.name));
        }
        bTree.close();
        Path indexPath = db.dir.resolve(key + ".idx");
        try {
            Files.deleteIfExists(indexPath);
        } catch (IOException e) {
            log.warn("Could not delete index file: {}", indexPath, e);
        }
        rewriteMeta(db);
        log.info("Dropped index '{}' from database '{}'", indexName, db.name);
    }

    Optional<dev.koradb.index.Index> getIndexIn(String dbName, String indexName) {
        if (dbName == null) return Optional.empty();
        OpenDb db = open(dbName);
        return Optional.ofNullable(db.indexes.get(indexName.toLowerCase()));
    }

    Optional<dev.koradb.index.Index> getIndexForColumnIn(String dbName, String tableName, String columnName) {
        if (dbName == null) return Optional.empty();
        OpenDb db = open(dbName);
        return db.indexes.values().stream()
                .filter(idx -> idx.getTableName().equalsIgnoreCase(tableName)
                        && idx.getColumnName().equalsIgnoreCase(columnName))
                .sorted(Comparator.comparing(dev.koradb.index.BPlusTree::getName))
                .map(idx -> (dev.koradb.index.Index) idx)
                .findFirst();
    }

    List<dev.koradb.index.Index> getIndexesForTableIn(String dbName, String tableName) {
        if (dbName == null) return List.of();
        OpenDb db = open(dbName);
        return db.indexes.values().stream()
                .filter(idx -> idx.getTableName().equalsIgnoreCase(tableName))
                .sorted(Comparator.comparing(dev.koradb.index.BPlusTree::getName))
                .map(idx -> (dev.koradb.index.Index) idx)
                .toList();
    }

    /** Cached planner statistics per table, with the table's write count when they were computed. */
    private record CachedStats(TableStatistics stats, long modCount) {}
    private final Map<TableFile, CachedStats> statsCache = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Planner statistics for a table. Computing them scans the whole table, so they are cached and
     * recomputed only after roughly 10% of the table (at least 100 rows) has been written since.
     */
    TableStatistics getTableStatisticsIn(String dbName, String tableName) {
        Table table = getTableIn(dbName, tableName);
        if (table instanceof DiskTable dt) {
            TableFile tf = dt.getTableFile();
            long mods = tf.getModCount();
            CachedStats cached = statsCache.get(tf);
            if (cached != null) {
                long threshold = Math.max(100L, cached.stats().rowCount() / 10);
                if (mods - cached.modCount() < threshold) {
                    return cached.stats();
                }
            }
            TableStatistics fresh = computeTableStatistics(table, tableName);
            statsCache.put(tf, new CachedStats(fresh, mods));
            return fresh;
        }
        return computeTableStatistics(table, tableName);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private TableStatistics computeTableStatistics(Table table, String tableName) {
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
            colStats.put(col.name(), new ColumnStatistics(col.name(), Math.max(1, distinctValues.size()), nulls, min, max));
        }
        return new TableStatistics(tableName, rowCount, pageCount, colStats);
    }

    dev.koradb.storage.wal.LogManager getLogManagerOf(String dbName) {
        if (dbName == null) return null;
        OpenDb db = openDbs.get(dbName.toLowerCase());
        return db != null ? db.logManager : null;
    }

    // =========================================================================
    // Catalog interface — default view (current database of this catalog object)
    // =========================================================================

    @Override public void createTable(Table table)        { createTableIn(currentDbName, table); }
    @Override public void dropTable(String tableName)      { dropTableIn(currentDbName, tableName); }
    @Override public Table getTable(String tableName)      { return getTableIn(currentDbName, tableName); }
    @Override public List<Table> listTables()              { return listTablesIn(currentDbName); }

    @Override
    public void createIndex(String indexName, String tableName, String columnName, boolean unique) {
        createIndexIn(currentDbName, indexName, tableName, columnName, unique);
    }

    @Override public void dropIndex(String indexName)      { dropIndexIn(currentDbName, indexName); }

    @Override
    public Optional<dev.koradb.index.Index> getIndex(String indexName) {
        return getIndexIn(currentDbName, indexName);
    }

    @Override
    public Optional<dev.koradb.index.Index> getIndexForColumn(String tableName, String columnName) {
        return getIndexForColumnIn(currentDbName, tableName, columnName);
    }

    @Override
    public List<dev.koradb.index.Index> getIndexesForTable(String tableName) {
        return getIndexesForTableIn(currentDbName, tableName);
    }

    @Override
    public TableStatistics getTableStatistics(String tableName) {
        return getTableStatisticsIn(currentDbName, tableName);
    }

    /** WAL of the default view's current database (embedded API, replication, checkpoints). */
    @Override
    public dev.koradb.storage.wal.LogManager getLogManager() {
        return getLogManagerOf(currentDbName);
    }

    public dev.koradb.storage.recovery.RecoveryManager getRecoveryManager() {
        OpenDb db = currentDbName == null ? null : openDbs.get(currentDbName);
        if (db == null || db.logManager == null) {
            return null;
        }
        Path walPath = StorageConfig.walFilePath(db.dir);
        return new dev.koradb.storage.recovery.RecoveryManager(walPath, db.logManager, tn -> resolveFile(db, tn), () -> withToast(db.files.values()));
    }

    /** Table files of the default view's current database. */
    public Collection<TableFile> getOpenTableFiles() {
        OpenDb db = currentDbName == null ? null : openDbs.get(currentDbName);
        return db == null ? List.of() : withToast(db.files.values());
    }

    /** Resolves a WAL table name; "t.toast" names the overflow file of table t. */
    private static TableFile resolveFile(OpenDb db, String tableName) {
        if (tableName == null) return null;
        String key = tableName.toLowerCase();
        if (TableFile.isToastName(key)) {
            TableFile main = db.files.get(key.substring(0, key.length() - ".toast".length()));
            return main == null ? null : main.toastTable(true);
        }
        return db.files.get(key);
    }

    /** The given table files plus their open overflow files. */
    private static Collection<TableFile> withToast(Collection<TableFile> files) {
        List<TableFile> all = new ArrayList<>(files);
        for (TableFile tf : files) {
            TableFile t = tf.toastTable(false);
            if (t != null) all.add(t);
        }
        return Collections.unmodifiableList(all);
    }

    /** WALs of every open database (metrics). */
    public Collection<dev.koradb.storage.wal.LogManager> getAllLogManagers() {
        List<dev.koradb.storage.wal.LogManager> all = new ArrayList<>();
        for (OpenDb db : openDbs.values()) if (db.logManager != null) all.add(db.logManager);
        return all;
    }

    /** Table files of every open database (metrics, buffer-pool status). */
    public Collection<TableFile> getAllOpenTableFiles() {
        List<TableFile> all = new ArrayList<>();
        for (OpenDb db : openDbs.values()) all.addAll(withToast(db.files.values()));
        return all;
    }

    public TableFile getTableFile(String tableName) {
        if (tableName == null || currentDbName == null) return null;
        OpenDb db = openDbs.get(currentDbName);
        return db == null ? null : resolveFile(db, tableName);
    }

    public Path getDataDir() {
        return dataDir;
    }

    /**
     * Files of one database (relative path under the data directory -> bytes), used as the initial
     * snapshot a replica receives before WAL streaming starts. Pass {@code catalog::snapshotFiles}
     * as the snapshot supplier of a {@code ReplicationMaster}.
     */
    public Map<String, byte[]> snapshotFiles(String dbName) {
        Map<String, byte[]> files = new java.util.HashMap<>();
        Path dir = dataDir.resolve(dbName.toLowerCase());
        if (!Files.exists(dir)) return files;
        OpenDb db = openDbs.get(dbName.toLowerCase());
        if (db != null) {
            for (TableFile tf : withToast(db.files.values())) {
                try { tf.getBufferPool().flushAll(); } catch (RuntimeException e) { log.warn("Flush before snapshot failed", e); }
            }
        }
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    files.put(dataDir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                } catch (IOException ignored) {
                    // file vanished (e.g. dropped table) while walking
                }
            });
        } catch (IOException e) {
            log.warn("Could not read database '{}' for a snapshot: {}", dbName, e.getMessage());
        }
        return files;
    }

    @Override
    public synchronized void close() {
        for (OpenDb db : openDbs.values()) {
            closeDb(db);
        }
        openDbs.clear();
        // currentDbName is kept: as before, a closed catalog transparently reopens the database on next use.
        log.debug("DiskCatalog closed");
    }

    // =========================================================================
    // Metadata helpers
    // =========================================================================

    private void closeDb(OpenDb db) {
        db.indexes.values().forEach(dev.koradb.index.BPlusTree::close);
        db.indexes.clear();
        db.files.values().forEach(TableFile::close);
        db.files.clear();
        if (db.logManager != null) {
            db.logManager.close();
        }
    }

    private void appendToMeta(Path dbDir, Table table) {
        String line = tableToMetaLine(table) + "\n";
        try {
            Files.writeString(metaFile(dbDir), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new StorageException("Cannot write catalog.meta in " + dbDir, e);
        }
    }

    private void appendIndexToMeta(Path dbDir, String indexName, String tableName, String columnName, boolean unique) {
        String line = "INDEX " + indexName + " " + tableName + " " + columnName + " " + unique + "\n";
        try {
            Files.writeString(metaFile(dbDir), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new StorageException("Cannot write index to catalog.meta in " + dbDir, e);
        }
    }

    private void rewriteMeta(OpenDb db) {
        Path metaPath = metaFile(db.dir);
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
                            return db.files.containsKey(parts[1].toLowerCase());
                        } else if (parts[0].equalsIgnoreCase("INDEX")) {
                            return db.indexes.containsKey(parts[1].toLowerCase());
                        }
                        return false;
                    })
                    .toList();
            Files.writeString(metaPath, String.join("\n", kept) + (kept.isEmpty() ? "" : "\n"),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("Cannot rewrite catalog.meta in " + db.dir, e);
        }
    }

    private record IndexMeta(String name, String tableName, String columnName, boolean unique) {}

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

    private Path dbDir(String dbName) {
        return dataDir.resolve(dbName.toLowerCase());
    }

    private Path metaFile(Path dbDir) {
        return dbDir.resolve(StorageConfig.CATALOG_META_FILE);
    }

    private static String requireName(String dbName) {
        if (dbName == null) {
            throw new SchemaException("No database selected. Use 'USE <database>' first.");
        }
        return dbName.toLowerCase();
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
