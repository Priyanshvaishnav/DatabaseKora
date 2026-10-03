package dev.koradb.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single database — a named container for tables.
 *
 * <p>Tables are stored in a {@link LinkedHashMap} so {@code SHOW TABLES}
 * returns them in insertion order.
 */
public final class Database {

    private final String             name;
    private final Map<String, Table> tables;

    public Database(String name) {
        this.name   = name;
        this.tables = new LinkedHashMap<>();
    }

    public String getName() { return name; }

    /** Returns the named table, or {@code null} if it doesn't exist. */
    public Table getTable(String tableName) {
        return tables.get(tableName.toLowerCase());
    }

    /** Adds a table. Caller must verify uniqueness first. */
    public void addTable(Table table) {
        tables.put(table.getName().toLowerCase(), table);
    }

    /** Removes a table. Caller must verify existence first. */
    public void removeTable(String tableName) {
        tables.remove(tableName.toLowerCase());
    }

    public boolean hasTable(String tableName) {
        return tables.containsKey(tableName.toLowerCase());
    }

    public List<Table> listTables() {
        return Collections.unmodifiableList(List.copyOf(tables.values()));
    }

    @Override
    public String toString() {
        return "Database{name='%s', tables=%s}".formatted(name, tables.keySet());
    }
}
