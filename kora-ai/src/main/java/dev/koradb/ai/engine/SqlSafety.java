package dev.koradb.ai.engine;

import dev.koradb.ai.schema.DatabaseSchema;
import dev.koradb.ai.schema.TableSchema;
import dev.koradb.sql.ast.*;
import dev.koradb.sql.lexer.Lexer;
import dev.koradb.sql.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Classification and schema checks for SQL produced by an LLM, before it is executed.
 */
public final class SqlSafety {

    private SqlSafety() {}

    /** What a statement does, from least to most dangerous. */
    public enum StatementKind {
        /** SELECT, SHOW, DESCRIBE, EXPLAIN (without ANALYZE of a write). */
        READ,
        /** INSERT, UPDATE, DELETE. */
        WRITE,
        /** CREATE / DROP of databases, tables and indexes; USE. */
        DDL,
        /** BEGIN, COMMIT, ROLLBACK. */
        TRANSACTION
    }

    /** Parses exactly one statement; throws the parser's exception otherwise. */
    public static Statement parseSingle(String sql) {
        return new Parser(new Lexer(sql).tokenize()).parse();
    }

    public static StatementKind classify(Statement stmt) {
        return switch (stmt) {
            case SelectStmt s -> StatementKind.READ;
            case ShowStmt s -> StatementKind.READ;
            case ExplainStmt s -> s.analyze() ? classify(s.statement()) : StatementKind.READ;
            case InsertStmt s -> StatementKind.WRITE;
            case UpdateStmt s -> StatementKind.WRITE;
            case DeleteStmt s -> StatementKind.WRITE;
            case BeginStmt s -> StatementKind.TRANSACTION;
            case CommitStmt s -> StatementKind.TRANSACTION;
            case RollbackStmt s -> StatementKind.TRANSACTION;
            default -> StatementKind.DDL;
        };
    }

    /** The statement's verb for messages ("DELETE", "DROP TABLE", ...). */
    public static String verb(Statement stmt) {
        return switch (stmt) {
            case ShowStmt s -> s.kind() == ShowStmt.Kind.COLUMNS ? "DESCRIBE" : "SHOW";
            case ExplainStmt s -> "EXPLAIN";
            default -> stmt.getClass().getSimpleName().replace("Stmt", "")
                    .replaceAll("([a-z])([A-Z])", "$1 $2").toUpperCase(Locale.ROOT);
        };
    }

    /**
     * Checks that the tables and columns a statement refers to exist in {@code schema}.
     *
     * @return null when everything resolves (or no schema is known), else a message naming what is missing
     */
    public static String checkAgainstSchema(Statement stmt, DatabaseSchema schema) {
        if (schema == null || schema.tables().isEmpty()) return null;
        return switch (stmt) {
            case SelectStmt s -> checkSelect(s, schema);
            case ExplainStmt s -> checkAgainstSchema(s.statement(), schema);
            case ShowStmt s -> s.kind() == ShowStmt.Kind.COLUMNS ? tableError(s.tableName(), schema) : null;
            case InsertStmt s -> {
                String e = tableError(s.tableName(), schema);
                if (e != null) yield e;
                int n = schema.getTable(s.tableName()).columns().size();
                yield s.values().size() == n ? null
                        : "INSERT INTO %s has %d value(s) but the table has %d column(s)".formatted(s.tableName(), s.values().size(), n);
            }
            case UpdateStmt s -> {
                String e = tableError(s.tableName(), schema);
                if (e != null) yield e;
                List<String> cols = new ArrayList<>();
                s.assignments().forEach(a -> cols.add(a.columnName()));
                s.where().ifPresent(w -> collectColumns(w, cols));
                yield columnsError(cols, List.of(schema.getTable(s.tableName())), List.of());
            }
            case DeleteStmt s -> {
                String e = tableError(s.tableName(), schema);
                if (e != null) yield e;
                List<String> cols = new ArrayList<>();
                s.where().ifPresent(w -> collectColumns(w, cols));
                yield columnsError(cols, List.of(schema.getTable(s.tableName())), List.of());
            }
            case DropTableStmt s -> tableError(s.tableName(), schema);
            case CreateIndexStmt s -> {
                String e = tableError(s.tableName(), schema);
                if (e != null) yield e;
                yield columnsError(List.of(s.columnName()), List.of(schema.getTable(s.tableName())), List.of());
            }
            default -> null;
        };
    }

    private static String checkSelect(SelectStmt s, DatabaseSchema schema) {
        String e = tableError(s.tableName(), schema);
        if (e != null) return e;
        List<TableSchema> tables = new ArrayList<>();
        tables.add(schema.getTable(s.tableName()));
        List<String> cols = new ArrayList<>();
        if (s.join().isPresent()) {
            e = tableError(s.join().get().tableName(), schema);
            if (e != null) return e;
            tables.add(schema.getTable(s.join().get().tableName()));
            collectColumns(s.join().get().onCondition(), cols);
        }
        List<String> aliases = new ArrayList<>();
        for (SelectItem item : s.selectItems()) {
            if (item.aggregateType() != null) {
                if (!"*".equals(item.aggregateColumn())) cols.add(item.aggregateColumn());
            } else if (!"*".equals(item.expression())) {
                cols.add(item.expression());
            }
            if (item.alias() != null) aliases.add(item.alias());
        }
        s.where().ifPresent(w -> collectColumns(w, cols));
        s.groupBy().ifPresent(cols::add);
        List<String> orderCols = s.orderBy().stream().map(OrderItem::columnName).toList();
        e = columnsError(cols, tables, List.of());
        return e != null ? e : columnsError(orderCols, tables, aliases);
    }

    private static String tableError(String table, DatabaseSchema schema) {
        if (schema.hasTable(table)) return null;
        return "Table '%s' does not exist. Available tables: %s".formatted(table,
                schema.tables().values().stream().map(TableSchema::tableName).collect(Collectors.joining(", ")));
    }

    private static String columnsError(List<String> cols, List<TableSchema> tables, List<String> aliases) {
        for (String col : cols) {
            if (aliases.stream().anyMatch(a -> a.equalsIgnoreCase(col))) continue;
            if (!resolves(col, tables)) {
                return "Column '%s' does not exist in %s (columns: %s)".formatted(col,
                        tables.stream().map(TableSchema::tableName).collect(Collectors.joining(" / ")),
                        tables.stream().flatMap(t -> t.columns().stream().map(c -> c.name())).distinct()
                                .collect(Collectors.joining(", ")));
            }
        }
        return null;
    }

    private static boolean resolves(String col, List<TableSchema> tables) {
        int dot = col.indexOf('.');
        if (dot > 0) {
            String t = col.substring(0, dot), c = col.substring(dot + 1);
            return tables.stream().anyMatch(ts -> ts.tableName().equalsIgnoreCase(t) && ts.hasColumn(c));
        }
        return tables.stream().anyMatch(ts -> ts.hasColumn(col));
    }

    private static void collectColumns(WhereClause where, List<String> out) {
        for (BinaryExpr p : where.predicates()) collectColumns(p, out);
    }

    private static void collectColumns(Expression expr, List<String> out) {
        switch (expr) {
            case ColumnRefExpr c -> out.add(c.columnName());
            case BinaryExpr b -> {
                collectColumns(b.left(), out);
                collectColumns(b.right(), out);
            }
            default -> { }
        }
    }
}
