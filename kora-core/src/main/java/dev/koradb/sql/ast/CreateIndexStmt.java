package dev.koradb.sql.ast;

/**
 * {@code CREATE [UNIQUE] INDEX <indexName> ON <tableName> ( <columnName> )}
 *
 * @param indexName  name of the index
 * @param tableName  name of the table
 * @param columnName name of the column indexed
 * @param unique     true if duplicate keys are prohibited
 */
public record CreateIndexStmt(String indexName, String tableName, String columnName, boolean unique, boolean ifNotExists)
        implements Statement {

    public CreateIndexStmt {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("Index name must not be blank");
        }
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Table name must not be blank");
        }
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("Column name must not be blank");
        }
    }

    public CreateIndexStmt(String indexName, String tableName, String columnName, boolean unique) {
        this(indexName, tableName, columnName, unique, false);
    }
}
