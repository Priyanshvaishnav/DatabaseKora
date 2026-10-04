package dev.koradb.sql.ast;

/**
 * {@code DROP INDEX <indexName> [ON <tableName>]}
 *
 * @param indexName name of the index to drop
 * @param tableName optional table name the index belongs to (may be null)
 */
public record DropIndexStmt(String indexName, String tableName)
        implements Statement {

    public DropIndexStmt {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("Index name must not be blank");
        }
    }
}
