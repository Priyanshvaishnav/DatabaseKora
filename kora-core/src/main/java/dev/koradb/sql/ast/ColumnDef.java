package dev.koradb.sql.ast;

/**
 * A column definition inside a CREATE TABLE statement.
 *
 * <pre>
 *   CREATE TABLE users (
 *       id    INT,
 *       name  VARCHAR,
 *       age   INT
 *   );
 * </pre>
 *
 * @param name     the column name (case-preserved, must be unique within the table)
 * @param dataType the SQL data type of the column
 */
public record ColumnDef(String name, DataType dataType) {

    @Override
    public String toString() {
        return "%s %s".formatted(name, dataType);
    }
}
