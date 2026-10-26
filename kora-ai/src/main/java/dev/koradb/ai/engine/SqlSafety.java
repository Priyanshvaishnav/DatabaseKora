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
            case TruncateStmt s -> StatementKind.WRITE;
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
                if (s.columns() != null) {
                    String ce = columnsError(s.columns(), single(s.tableName(), schema), List.of());
                    if (ce != null) yield ce;
                }
                int n = s.columns() != null ? s.columns().size() : schema.getTable(s.tableName()).columns().size();
                for (var row : s.rows()) {
                    if (row.size() != n) {
                        yield "INSERT INTO %s has %d value(s) but %d column(s) are being filled".formatted(s.tableName(), row.size(), n);
                    }
                }
                yield null;
            }
            case UpdateStmt s -> {
                String e = tableError(s.tableName(), schema);
                if (e != null) yield e;
                List<String> cols = new ArrayList<>();
                s.assignments().forEach(a -> {
                    cols.add(a.columnName());
                    ExpressionUtils.collectColumns(a.value(), cols);
                });
                s.where().ifPresent(w -> collectColumns(w, cols));
                yield columnsError(cols, single(s.tableName(), schema), List.of());
            }
            case DeleteStmt s -> {
                String e = tableError(s.tableName(), schema);
                if (e != null) yield e;
                List<String> cols = new ArrayList<>();
                s.where().ifPresent(w -> collectColumns(w, cols));
                yield columnsError(cols, single(s.tableName(), schema), List.of());
            }
            case DropTableStmt s -> s.ifExists() ? null : tableError(s.tableName(), schema);
            case TruncateStmt s -> tableError(s.tableName(), schema);
            case AlterTableStmt s -> tableError(s.tableName(), schema);
            case CreateIndexStmt s -> {
                String e = tableError(s.tableName(), schema);
                if (e != null) yield e;
                yield columnsError(List.of(s.columnName()), single(s.tableName(), schema), List.of());
            }
            default -> null;
        };
    }

    private static String checkSelect(SelectStmt s, DatabaseSchema schema) {
        if (s.tableName() == null) return null;                    // SELECT 1
        String e = tableError(s.tableName(), schema);
        if (e != null) return e;
        // tables by the name rows are qualified with (alias or table name)
        java.util.Map<String, TableSchema> sources = new java.util.LinkedHashMap<>();
        sources.put(s.sourceName().toLowerCase(Locale.ROOT), schema.getTable(s.tableName()));
        List<String> cols = new ArrayList<>();
        for (JoinClause j : s.joins()) {
            e = tableError(j.tableName(), schema);
            if (e != null) return e;
            sources.put(j.sourceName().toLowerCase(Locale.ROOT), schema.getTable(j.tableName()));
            if (j.onCondition() != null) ExpressionUtils.collectColumns(j.onCondition(), cols);
        }
        List<String> aliases = new ArrayList<>();
        for (SelectItem item : s.selectItems()) {
            if (item.aggregateType() != null) {
                if (!"*".equals(item.aggregateColumn())) cols.add(item.aggregateColumn());
            } else if (item.isComputed()) {
                ExpressionUtils.collectColumns(item.valueExpr(), cols);
            } else {
                cols.add(item.expression());
            }
            if (item.alias() != null) aliases.add(item.alias());
        }
        s.where().ifPresent(w -> collectColumns(w, cols));
        cols.addAll(s.groupByColumns());
        s.having().ifPresent(h -> ExpressionUtils.collectColumns(h, cols));
        List<String> orderCols = s.orderBy().stream().map(OrderItem::columnName).filter(c -> !c.contains("(")).toList();
        e = columnsError(cols, sources, List.of());
        return e != null ? e : columnsError(orderCols, sources, aliases);
    }

    private static String tableError(String table, DatabaseSchema schema) {
        if (schema.hasTable(table)) return null;
        return "Table '%s' does not exist. Available tables: %s".formatted(table,
                schema.tables().values().stream().map(TableSchema::tableName).collect(Collectors.joining(", ")));
    }

    private static java.util.Map<String, TableSchema> single(String table, DatabaseSchema schema) {
        return java.util.Map.of(table.toLowerCase(Locale.ROOT), schema.getTable(table));
    }

    private static String columnsError(List<String> cols, java.util.Map<String, TableSchema> sources, List<String> aliases) {
        for (String col : cols) {
            if (aliases.stream().anyMatch(a -> a.equalsIgnoreCase(col))) continue;
            if (!resolves(col, sources)) {
                return "Column '%s' does not exist in %s (columns: %s)".formatted(col,
                        sources.values().stream().map(TableSchema::tableName).distinct().collect(Collectors.joining(" / ")),
                        sources.values().stream().flatMap(t -> t.columns().stream().map(c -> c.name())).distinct()
                                .collect(Collectors.joining(", ")));
            }
        }
        return null;
    }

    private static boolean resolves(String col, java.util.Map<String, TableSchema> sources) {
        int dot = col.indexOf('.');
        if (dot > 0) {
            TableSchema t = sources.get(col.substring(0, dot).toLowerCase(Locale.ROOT));
            return t != null && t.hasColumn(col.substring(dot + 1));
        }
        return sources.values().stream().anyMatch(ts -> ts.hasColumn(col));
    }

    private static void collectColumns(WhereClause where, List<String> out) {
        for (Expression p : where.predicates()) ExpressionUtils.collectColumns(p, out);
    }
}
