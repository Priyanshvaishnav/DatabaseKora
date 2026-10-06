package dev.koradb.ai.prompt;

/**
 * Strict dialect constraints and syntax guidelines for KoraDB SQL generation.
 */
public final class DialectRules {

    private DialectRules() {}

    public static final String KORADB_SQL_GUIDELINES = """
            KoraDB SQL Dialect Guidelines:
            1. Supported Statements:
               - SELECT [columns] FROM [table] [JOIN other_table ON condition] [WHERE condition] [GROUP BY column] [ORDER BY column [ASC|DESC]] [LIMIT n [OFFSET m]];
               - INSERT INTO [table] VALUES (val1, val2, ...);
               - UPDATE [table] SET column = literal_value [WHERE condition];
               - DELETE FROM [table] [WHERE condition];
               - CREATE TABLE [table] (col1 TYPE, col2 TYPE, ...);
               - CREATE [UNIQUE] INDEX [index_name] ON [table] (column);
               - DROP TABLE [table];
               - DROP INDEX [index_name];

            2. Grammar & Syntax Restrictions:
               - DO NOT use table aliases (e.g., 'FROM users u' is NOT supported; write 'FROM users JOIN orders ON users.id = orders.user_id').
               - UPDATE statements require literal values in assignments (e.g., 'SET balance = 1500.0', NOT expressions like 'SET balance = balance + 10').
               - String literals MUST use single quotes ('example').
               - Supported Data Types: INT, BIGINT, BOOLEAN, VARCHAR, DOUBLE, TIMESTAMP.
               - Supported Aggregate Functions: COUNT(*), COUNT(col), SUM(col), AVG(col), MIN(col), MAX(col).
               - Every statement should terminate with a semicolon ';'.
            """;
}
