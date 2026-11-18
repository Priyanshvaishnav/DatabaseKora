package dev.koradb.cli;

import dev.koradb.ai.engine.KoraAiEngine;
import dev.koradb.ai.engine.SqlSafety;
import dev.koradb.ai.model.ExplanationResult;
import dev.koradb.ai.model.OptimizationReport;
import dev.koradb.ai.model.TranslationResult;
import dev.koradb.ai.provider.RuleBasedLlmClient;
import dev.koradb.ai.schema.DatabaseSchema;
import dev.koradb.ai.schema.SchemaExtractor;
import dev.koradb.client.ClientQueryResult;
import dev.koradb.client.KoraClient;
import dev.koradb.client.KoraClientException;
import dev.koradb.sql.ast.ColumnDef;
import dev.koradb.sql.ast.DataType;
import dev.koradb.sql.ast.Statement;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Interactive Command-Line Interface and REPL for KoraDB.
 */
public final class KoraCli {

    public static final String BANNER = """
            ██╗  ██╗ ██████╗ ██████╗  █████╗ ██████╗ ██████╗\s
            ██║ ██╔╝██╔═══██╗██╔══██╗██╔══██╗██╔══██╗██╔══██╗
            █████╔╝ ██║   ██║██████╔╝███████║██║  ██║██████╔╝
            ██╔═██╗ ██║   ██║██╔══██╗██╔══██║██║  ██║██╔══██╗
            ██║  ██╗╚██████╔╝██║  ██║██║  ██║██████╔╝██████╔╝
            ╚═╝  ╚═╝ ╚═════╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚═════╝ ╚═════╝\s
            Welcome to KoraDB CLI (v2.0.1)
            Type \\h or \\help for help. Type \\q to exit.
            """;

    private final CliConfig config;
    private final BufferedReader reader;
    private final PrintStream out;
    private String currentDatabase;
    private KoraAiEngine aiEngine = new KoraAiEngine();

    /** For tests: the AI engine \ai and the other AI commands use. */
    void setAiEngine(KoraAiEngine aiEngine) {
        this.aiEngine = aiEngine;
    }

    /** Banner for consoles whose character set cannot show the block characters (e.g. Windows code page 437/1252). */
    public static final String ASCII_BANNER = """
             _  __                ____  ____
            | |/ /___  _ __ __ _ |  _ \\| __ )
            | ' // _ \\| '__/ _` || | | |  _ \\
            | . \\ (_) | | | (_| || |_| | |_) |
            |_|\\_\\___/|_|  \\__,_||____/|____/
            Welcome to KoraDB CLI (v2.0.1)
            Type \\h or \\help for help. Type \\q to exit.
            """;

    private final java.nio.charset.Charset outCharset;

    public KoraCli(CliConfig config, InputStream in, PrintStream out) {
        this(config, in, out, StandardCharsets.UTF_8);
    }

    /**
     * @param charset character set of the console: used to decode typed input; output falls back
     *                to ASCII decorations when the charset cannot show box/block characters
     */
    public KoraCli(CliConfig config, InputStream in, PrintStream out, java.nio.charset.Charset charset) {
        this.config = config;
        this.reader = new BufferedReader(new InputStreamReader(in, charset));
        this.out = out;
        this.outCharset = charset;
        this.currentDatabase = config.database() != null ? config.database().trim() : "";
    }

    /**
     * Executes the CLI session (interactive REPL or one-shot mode).
     *
     * @return 0 on success, non-zero on failure
     */
    public int start() {
        if (config.help()) {
            printHelp();
            return 0;
        }

        if (config.version()) {
            out.println("KoraDB CLI v2.0.1");
            return 0;
        }

        try (KoraClient client = KoraClient.connect(config.host(), config.port(), config.database(),
                config.user(), config.password())) {
            // One-shot execution mode (-e "<sql>")
            if (config.executeSql() != null && !config.executeSql().isBlank()) {
                return executeOneShot(client, config.executeSql());
            }

            // Interactive REPL mode
            return runRepl(client);

        } catch (KoraClientException e) {
            out.println("Failed to connect to KoraDB at " + config.host() + ":" + config.port() + ": " + e.getMessage());
            return 1;
        } catch (IOException e) {
            out.println("Network error connecting to " + config.host() + ":" + config.port() + ": " + e.getMessage());
            return 1;
        }
    }

    private int executeOneShot(KoraClient client, String sql) {
        try {
            ClientQueryResult result = client.execute(sql);
            out.print(TableFormatter.format(result));
            return 0;
        } catch (KoraClientException e) {
            out.println("ERROR: " + e.getMessage());
            return 1;
        }
    }

    public int runRepl(KoraClient client) {
        out.println(outCharset.newEncoder().canEncode("█╗═") ? BANNER : ASCII_BANNER);
        out.printf("Connected to %s:%d (connId=%d)%n%n",
                config.host(), config.port(), client.getConnectionId());

        StringBuilder buffer = new StringBuilder();

        while (true) {
            String dbPrompt = currentDatabase.isEmpty() ? "(none)" : currentDatabase;
            String prompt = buffer.isEmpty() ? "kora [" + dbPrompt + "]> " : "   -> ";
            out.print(prompt);
            out.flush();

            String line;
            try {
                line = reader.readLine();
            } catch (IOException e) {
                break;
            }

            if (line == null) {
                out.println();
                break; // EOF
            }

            String trimmed = line.trim();

            if (trimmed.isEmpty() && buffer.isEmpty()) {
                continue;
            }

            // Handle meta-commands
            if (trimmed.startsWith("\\") || trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit")) {
                if (handleMetaCommand(client, trimmed, buffer)) {
                    break; // Exit requested
                }
                continue;
            }

            // Convenience: single-line administrative commands do not strictly require a semicolon
            boolean isConvenienceCmd = buffer.isEmpty() && (
                    trimmed.toUpperCase().startsWith("SHOW ") ||
                    trimmed.toUpperCase().startsWith("DESCRIBE ") ||
                    trimmed.toUpperCase().startsWith("DESC ") ||
                    trimmed.toUpperCase().startsWith("USE ")
            );

            // Accumulate query
            if (buffer.isEmpty()) {
                buffer.append(line);
            } else {
                buffer.append(" ").append(line);
            }

            // Check if query is terminated by semicolon or is a convenience command
            if (trimmed.endsWith(";") || isConvenienceCmd) {
                String sql = buffer.toString().trim();
                buffer.setLength(0);
                if (!sql.endsWith(";")) {
                    sql = sql + ";";
                }
                executeSql(client, sql);
            }
        }

        out.println("Bye");
        return 0;
    }

    private void executeSql(KoraClient client, String sql) {
        try {
            ClientQueryResult res = client.execute(sql);

            // Track USE <database>
            String upper = sql.toUpperCase().replaceAll("\\s+", " ").trim();
            if (upper.startsWith("USE ")) {
                String dbName = upper.substring(4);
                if (dbName.endsWith(";")) dbName = dbName.substring(0, dbName.length() - 1);
                currentDatabase = dbName.trim().toLowerCase();
            }

            out.print(TableFormatter.format(res));

        } catch (KoraClientException e) {
            out.println("ERROR: " + e.getMessage());
        }
    }

    private boolean handleMetaCommand(KoraClient client, String cmd, StringBuilder buffer) {
        String[] parts = cmd.trim().split("\\s+", 2);
        String meta = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (meta) {
            case "\\q", "\\quit", "exit", "quit" -> {
                return true;
            }
            case "\\h", "\\help", "\\?" -> {
                printReplHelp();
            }
            case "\\l", "\\databases" -> {
                executeSql(client, "SHOW DATABASES;");
            }
            case "\\dt", "\\tables" -> {
                executeSql(client, "SHOW TABLES;");
            }
            case "\\d", "\\describe" -> {
                if (arg.isEmpty()) {
                    out.println("Usage: \\d <table_name>");
                } else {
                    executeSql(client, "DESCRIBE " + arg + ";");
                }
            }
            case "\\c", "\\use" -> {
                if (arg.isEmpty()) {
                    // Just \c alone cancels current multi-line query
                    buffer.setLength(0);
                    out.println("Query buffer cleared.");
                } else {
                    executeSql(client, "USE " + arg + ";");
                }
            }
            case "\\s", "\\status" -> {
                out.printf("Connection:  %s:%d%n", config.host(), config.port());
                out.printf("Server:      %s%n", client.getServerVersion());
                out.printf("Session ID:  %d%n", client.getConnectionId());
                out.printf("Database:    %s%n", currentDatabase.isEmpty() ? "(none)" : currentDatabase);
                out.printf("AI Provider: %s (%s)%n", aiEngine.getActiveProvider(), aiEngine.getActiveModel());
            }
            case "\\ai", "\\ask" -> {
                if (arg.isEmpty()) {
                    out.println("Usage: \\ai <natural language prompt>");
                } else {
                    handleAiQuery(client, arg);
                }
            }
            case "\\explain-ai" -> {
                if (arg.isEmpty()) {
                    out.println("Usage: \\explain-ai <sql_query>");
                } else {
                    handleAiExplain(client, arg);
                }
            }
            case "\\advisor" -> {
                if (arg.isEmpty()) {
                    out.println("Usage: \\advisor <sql_query>");
                } else {
                    handleAiAdvisor(client, arg);
                }
            }
            case "\\ai-provider" -> {
                if (arg.isEmpty()) {
                    out.printf("Active AI Provider: %s (model: %s)%n", aiEngine.getActiveProvider(), aiEngine.getActiveModel());
                    out.println("Supported providers: rule-based, openai, gemini, anthropic");
                    out.println("Usage: \\ai-provider <provider_name>");
                } else {
                    try {
                        aiEngine = KoraAiEngine.withProvider(arg);
                        out.printf("Switched AI provider to: %s (model: %s)%n", aiEngine.getActiveProvider(), aiEngine.getActiveModel());
                    } catch (Exception e) {
                        out.println("Failed to switch AI provider: " + e.getMessage());
                    }
                }
            }
            default -> {
                out.println("Unknown command: " + meta + ". Type \\h for help.");
            }
        }
        return false;
    }

    /**
     * Translates {@code prompt} and runs the result with the same rules as {@code KoraAi.ask}: a read runs
     * after a confirmation that defaults to yes; a change (INSERT/UPDATE/DELETE or DDL) runs only when the
     * prompt asks for that kind of change and the user types "yes"; anything else is shown but not run.
     */
    private void handleAiQuery(KoraClient client, String prompt) {
        DatabaseSchema schema = fetchDatabaseSchema(client);
        // Sample column values are table data, so like KoraAi they go only to the offline provider by default.
        if (aiEngine.getLlmClient() instanceof RuleBasedLlmClient) {
            schema = SchemaExtractor.withSampleValues(schema, sql -> queryRows(client, sql), null);
        }
        out.println("Translating with KoraDB AI (" + aiEngine.getActiveProvider() + ")...");
        TranslationResult tr = aiEngine.translate(prompt, schema);

        out.println();
        out.println("Generated SQL: " + tr.generatedSql());
        out.printf("Provider:      %s (%s) [%dms]%n", tr.provider(), tr.model(), tr.durationMillis());

        if (!tr.isValid()) {
            out.println("Not executed: " + tr.errorMessage());
            return;
        }
        Statement stmt;
        try {
            stmt = SqlSafety.parseSingle(tr.generatedSql());
        } catch (RuntimeException e) {
            out.println("Not executed: the generated SQL could not be parsed: " + e.getMessage());
            return;
        }
        String verb = SqlSafety.verb(stmt);
        boolean confirmed;
        switch (SqlSafety.classify(stmt)) {
            case READ -> {
                String answer = ask("Execute query? [Y/n]: ");
                confirmed = answer != null && (answer.isBlank() || answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
            }
            case TRANSACTION -> {
                out.println("Not executed: \\ai runs single statements and does not manage transactions.");
                return;
            }
            default -> {        // WRITE, DDL
                if (!SqlSafety.requestAsksFor(prompt, stmt)) {
                    out.println("Not executed: your request does not ask for a " + verb
                            + ", so the generated statement does not match it.");
                    return;
                }
                String what = SqlSafety.classify(stmt) == SqlSafety.StatementKind.DDL ? "the schema" : "data";
                confirmed = "yes".equalsIgnoreCase(ask("This " + verb + " changes " + what + ". Type yes to run it: "));
            }
        }
        if (confirmed) {
            executeSql(client, tr.generatedSql());
        } else {
            out.println("Execution cancelled.");
        }
    }

    /** Prints {@code question} and returns the trimmed answer, or null at end of input. */
    private String ask(String question) {
        out.print(question);
        out.flush();
        try {
            String answer = reader.readLine();
            return answer != null ? answer.trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Runs a SELECT and returns its rows as column name to value. */
    private static List<Map<String, Object>> queryRows(KoraClient client, String sql) {
        ClientQueryResult res;
        try {
            res = client.execute(sql);
        } catch (KoraClientException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        List<Map<String, Object>> rows = new ArrayList<>(res.rows().size());
        for (List<Object> row : res.rows()) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < res.columns().size() && i < row.size(); i++) {
                values.put(res.columns().get(i), row.get(i));
            }
            rows.add(values);
        }
        return rows;
    }

    private void handleAiExplain(KoraClient client, String sql) {
        DatabaseSchema schema = fetchDatabaseSchema(client);
        ExplanationResult er = aiEngine.explain(sql, schema);
        out.println();
        out.println(er.explanation());
    }

    private void handleAiAdvisor(KoraClient client, String sql) {
        DatabaseSchema schema = fetchDatabaseSchema(client);
        OptimizationReport rep = aiEngine.advise(sql, schema);
        out.println();
        out.println(rep.analysis());
        if (!rep.recommendedIndexDdls().isEmpty()) {
            out.println("\nRecommended DDL Statements:");
            for (String ddl : rep.recommendedIndexDdls()) {
                out.println("  " + ddl);
            }
        }
    }

    private DatabaseSchema fetchDatabaseSchema(KoraClient client) {
        if (currentDatabase == null || currentDatabase.isBlank()) {
            return null;
        }
        try {
            ClientQueryResult tablesResult = client.execute("SHOW TABLES;");
            Map<String, List<ColumnDef>> tableCols = new LinkedHashMap<>();
            for (List<Object> row : tablesResult.rows()) {
                if (row.isEmpty()) continue;
                String tableName = String.valueOf(row.get(0));
                ClientQueryResult descResult = client.execute("DESCRIBE " + tableName + ";");
                List<ColumnDef> cols = new ArrayList<>();
                for (List<Object> colRow : descResult.rows()) {
                    if (colRow.size() < 2) continue;
                    String colName = String.valueOf(colRow.get(0));
                    String typeStr = String.valueOf(colRow.get(1)).toUpperCase();
                    try {
                        DataType dt = DataType.valueOf(typeStr);
                        cols.add(new ColumnDef(colName, dt));
                    } catch (IllegalArgumentException ignored) {
                        cols.add(new ColumnDef(colName, DataType.VARCHAR));
                    }
                }
                tableCols.put(tableName, cols);
            }
            return SchemaExtractor.fromMetadata(currentDatabase, tableCols, Map.of());
        } catch (Exception e) {
            return null;
        }
    }

    private void printReplHelp() {
        out.println("""
                List of KoraDB CLI commands:
                  \\l,  \\databases       List all databases
                  \\dt, \\tables          List all tables in active database
                  \\d   <table_name>     Describe schema of table
                  \\c   <db_name>        Connect to / use database
                  \\c                    Clear the current multi-line query buffer
                  \\s,  \\status          Display server and connection status
                  \\ai  <prompt>         Natural language query: shows the SQL, runs reads on Enter,
                                         changes only when asked for and confirmed with "yes"
                  \\explain-ai <sql>     Explain query plan in plain English
                  \\advisor <sql>        Analyze query and recommend B+ Tree indexes
                  \\ai-provider [name]   Display or switch active AI provider
                  \\h,  \\help            Display this help screen
                  \\q,  \\quit            Exit KoraDB CLI

                SQL Syntax Overview:
                  CREATE DATABASE <name>;
                  USE <name>;
                  CREATE TABLE <table_name> (col1 TYPE, col2 TYPE, ...);
                  INSERT INTO <table_name> VALUES (val1, val2, ...);
                  SELECT <cols> FROM <table_name> [WHERE <cond>] [ORDER BY <cols>] [LIMIT <n>];
                  UPDATE <table_name> SET <col> = <val> [WHERE <cond>];
                  DELETE FROM <table_name> [WHERE <cond>];
                  BEGIN; / COMMIT; / ROLLBACK;
                  EXPLAIN SELECT ...;
                """);
    }

    private void printHelp() {
        out.println("""
                Usage: koradb-cli [OPTIONS]
                
                Options:
                  -h, --host <HOST>      Database server host (default: 127.0.0.1)
                  -p, --port <PORT>      Database server port (default: 8765)
                  -d, --database <DB>    Initial database to select
                  -e, --execute <SQL>    Execute SQL statement and exit immediately
                  -u, --user <NAME>      User name, for servers started with a password (env: KORA_USER)
                  --password <SECRET>    Password (env: KORA_PASSWORD; prefer the env var so the
                                         password does not appear in the process list)
                  -v, --version          Print version information and exit
                  --help                 Show this help message and exit
                """);
    }
}
