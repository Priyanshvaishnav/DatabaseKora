package dev.koradb.ai.prompt;

/**
 * Strict dialect constraints and syntax guidelines for KoraDB SQL generation.
 */
public final class DialectRules {

    private DialectRules() {}

    public static final String KORADB_SQL_GUIDELINES = """
            KoraDB SQL Dialect Guidelines:
            1. Supported Statements:
               - SELECT [DISTINCT] columns|expressions FROM table [alias] [[LEFT] JOIN other [alias] ON condition]...
                 [WHERE condition] [GROUP BY col, ...] [HAVING condition] [ORDER BY col|alias [ASC|DESC], ...] [LIMIT n [OFFSET m]];
               - INSERT INTO table [(col, ...)] VALUES (val, ...)[, (val, ...)];
               - UPDATE table SET column = expression [, ...] [WHERE condition];
               - DELETE FROM table [WHERE condition];  TRUNCATE TABLE table;
               - CREATE TABLE [IF NOT EXISTS] table (col TYPE [NOT NULL] [PRIMARY KEY] [UNIQUE], ...);
               - ALTER TABLE table ADD COLUMN col TYPE;
               - CREATE [UNIQUE] INDEX name ON table (column);  DROP TABLE [IF EXISTS] table;  DROP INDEX name;

            2. Grammar & Syntax:
               - Conditions: =, != or <>, <, >, <=, >=, AND, OR, NOT, IN (...), BETWEEN a AND b, LIKE 'pattern', IS [NOT] NULL.
               - Arithmetic: + - * / % on numbers.
               - Subqueries: (SELECT ...) as a single value, x IN (SELECT ...), EXISTS (SELECT ...); they must not refer to outer tables.
               - String literals MUST use single quotes ('example'); identifiers may be double-quoted ("order").
               - Supported Data Types: INT, BIGINT, BOOLEAN, VARCHAR, DOUBLE, TIMESTAMP.
               - Supported Aggregate Functions: COUNT(*), COUNT(col), SUM(col), AVG(col), MIN(col), MAX(col) (on plain columns).
               - No other functions (no LOWER, COALESCE, NOW, CAST ...), no RIGHT/FULL joins, no INSERT ... SELECT.
               - Every statement should terminate with a semicolon ';'.
            """;

}
