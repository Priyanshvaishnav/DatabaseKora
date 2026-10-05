package dev.koradb.sql.ast;

/**
 * An item in an ORDER BY clause.
 *
 * @param columnName the name of the column to sort on
 * @param ascending  true for ASC (default), false for DESC
 */
public record OrderItem(String columnName, boolean ascending) {

    public OrderItem(String columnName) {
        this(columnName, true);
    }

    public OrderItem {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("Column name in ORDER BY must not be blank");
        }
    }
}
