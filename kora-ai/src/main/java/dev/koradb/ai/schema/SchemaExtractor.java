package dev.koradb.ai.schema;

import dev.koradb.catalog.Catalog;
import dev.koradb.catalog.Database;
import dev.koradb.catalog.Table;
import dev.koradb.index.Index;
import dev.koradb.sql.ast.ColumnDef;
import dev.koradb.sql.ast.DataType;

import java.util.*;

/**
 * Extracts and compiles schema metadata from KoraDB catalogs or query results
 * into structured {@link DatabaseSchema} instances for AI prompt context.
 */
public final class SchemaExtractor {

    private SchemaExtractor() {}

    /**
     * Extracts database schema directly from a KoraDB {@link Catalog}.
     *
     * @param catalog the active engine catalog
     * @param databaseName target database name
     * @return populated {@link DatabaseSchema}
     */
    public static DatabaseSchema fromCatalog(Catalog catalog, String databaseName) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        Objects.requireNonNull(databaseName, "databaseName must not be null");

        Database db = catalog.getDatabase(databaseName);
        if (db == null) {
            return new DatabaseSchema(databaseName, Map.of());
        }

        Map<String, TableSchema> tableMap = new LinkedHashMap<>();
        for (Table table : db.listTables()) {
            String tableName = table.getName();
            List<ColumnDef> columns = table.getSchema().columns();

            List<TableSchema.IndexInfo> indexes = new ArrayList<>();
            try {
                List<Index> indexList = catalog.getIndexesForTable(tableName);
                if (indexList != null) {
                    for (Index idx : indexList) {
                        indexes.add(new TableSchema.IndexInfo(
                                idx.getName(),
                                idx.getColumnName(),
                                idx.getKeyType(),
                                idx.isUnique()
                        ));
                    }
                }
            } catch (Exception ignored) {
                // If index lookup is unsupported or table has no indexes
            }

            tableMap.put(tableName.toLowerCase(), new TableSchema(tableName, columns, indexes));
        }

        return new DatabaseSchema(databaseName, tableMap);
    }

    /**
     * Reconstructs a {@link DatabaseSchema} from raw table and column metadata lists.
     * Useful when interacting over a network client where schema is retrieved via SHOW TABLES / DESCRIBE.
     */
    public static DatabaseSchema fromMetadata(String databaseName, Map<String, List<ColumnDef>> tableColumns,
                                             Map<String, List<TableSchema.IndexInfo>> tableIndexes) {
        Map<String, TableSchema> map = new LinkedHashMap<>();
        for (Map.Entry<String, List<ColumnDef>> entry : tableColumns.entrySet()) {
            String tableName = entry.getKey();
            List<ColumnDef> cols = entry.getValue();
            List<TableSchema.IndexInfo> idxs = tableIndexes != null ? tableIndexes.getOrDefault(tableName.toLowerCase(), List.of()) : List.of();
            map.put(tableName.toLowerCase(), new TableSchema(tableName, cols, idxs));
        }
        return new DatabaseSchema(databaseName, map);
    }

    /** Sample values of one table, kept for {@link #SAMPLE_TTL_MILLIS}. */
    public record CachedSamples(long takenAtMillis, Map<String, List<String>> values) {}

    static final long SAMPLE_TTL_MILLIS = 30_000;
    static final int SAMPLE_ROWS = 1000;
    static final int MAX_DISTINCT = 25;
    static final int MAX_VALUE_LENGTH = 40;

    /**
     * Returns {@code schema} with the distinct values of its low-cardinality VARCHAR columns
     * (at most {@value #MAX_DISTINCT} values of at most {@value #MAX_VALUE_LENGTH} characters, from the
     * first {@value #SAMPLE_ROWS} rows), e.g. order statuses or city names. These let a model map
     * "pending orders" to {@code status = 'pending'}. Results are cached per table for 30 seconds.
     */
    public static DatabaseSchema withSampleValues(DatabaseSchema schema, dev.koradb.KoraDB db,
                                                  Map<String, CachedSamples> cache) {
        if (schema == null || db == null) return schema;
        Map<String, List<String>> all = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (TableSchema ts : schema.tables().values()) {
            List<String> textCols = ts.columns().stream()
                    .filter(c -> c.dataType() == dev.koradb.sql.ast.DataType.VARCHAR)
                    .map(ColumnDef::name).toList();
            if (textCols.isEmpty()) continue;
            String key = (schema.databaseName() + "." + ts.tableName()).toLowerCase();
            CachedSamples cached = cache != null ? cache.get(key) : null;
            if (cached == null || now - cached.takenAtMillis() > SAMPLE_TTL_MILLIS) {
                cached = new CachedSamples(now, sample(db, ts.tableName(), textCols));
                if (cache != null) cache.put(key, cached);
            }
            all.putAll(cached.values());
        }
        return new DatabaseSchema(schema.databaseName(), schema.tables(), all);
    }

    private static Map<String, List<String>> sample(dev.koradb.KoraDB db, String table, List<String> cols) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        dev.koradb.execution.QueryResult qr;
        try {
            qr = db.execute("SELECT " + String.join(", ", cols) + " FROM " + table + " LIMIT " + SAMPLE_ROWS);
        } catch (RuntimeException e) {
            return out;   // samples are an optional hint
        }
        for (String col : cols) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            boolean tooMany = false;
            for (dev.koradb.catalog.Row r : qr.rows()) {
                Object v = r.get(col);
                if (!(v instanceof String str) || str.isBlank() || str.length() > MAX_VALUE_LENGTH) continue;
                counts.merge(str, 1, Integer::sum);
                if (counts.size() > MAX_DISTINCT) { tooMany = true; break; }
            }
            if (tooMany || counts.isEmpty()) continue;
            // a column whose values are all distinct (names, emails) is not categorical
            if (counts.size() > 1 && counts.size() == qr.rows().size() && qr.rows().size() > 5) continue;
            List<String> vals = new ArrayList<>(counts.keySet());
            vals.sort((a, b) -> Integer.compare(counts.get(b), counts.get(a)));
            out.put((table + "." + col).toLowerCase(), vals);
        }
        return out;
    }

}
