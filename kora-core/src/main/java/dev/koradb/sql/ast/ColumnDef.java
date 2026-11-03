package dev.koradb.sql.ast;

/**
 * A column: name, type, whether it rejects NULL (NOT NULL / PRIMARY KEY) and, for {@code VARCHAR(n)},
 * the longest value it accepts in characters (0 = no limit).
 */
public record ColumnDef(String name, DataType dataType, boolean notNull, int maxLength) {

    public ColumnDef(String name, DataType dataType, boolean notNull) {
        this(name, dataType, notNull, 0);
    }

    public ColumnDef(String name, DataType dataType) {
        this(name, dataType, false);
    }

    /** The type as written in SQL: {@code INT}, {@code VARCHAR} or {@code VARCHAR(50)}. */
    public String typeName() {
        return maxLength > 0 ? "%s(%d)".formatted(dataType, maxLength) : dataType.name();
    }

    @Override
    public String toString() {
        return "%s %s%s".formatted(name, typeName(), notNull ? " NOT NULL" : "");
    }
}
