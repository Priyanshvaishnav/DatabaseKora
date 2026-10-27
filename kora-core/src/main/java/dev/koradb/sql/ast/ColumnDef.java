package dev.koradb.sql.ast;

/**
 * A column: name, type and whether it rejects NULL (NOT NULL / PRIMARY KEY).
 */
public record ColumnDef(String name, DataType dataType, boolean notNull) {

    public ColumnDef(String name, DataType dataType) {
        this(name, dataType, false);
    }

    @Override
    public String toString() {
        return "%s %s%s".formatted(name, dataType, notNull ? " NOT NULL" : "");
    }
}
