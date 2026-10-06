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
}
