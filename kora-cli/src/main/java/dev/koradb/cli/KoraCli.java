package dev.koradb.cli;

import dev.koradb.client.ClientQueryResult;
import dev.koradb.client.KoraClient;
import dev.koradb.client.KoraClientException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

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
            Welcome to KoraDB CLI (v0.1.0-SNAPSHOT)
            Type \\h or \\help for help. Type \\q to exit.
            """;

    private final CliConfig config;
    private final BufferedReader reader;
    private final PrintStream out;
    private String currentDatabase;

    public KoraCli(CliConfig config, InputStream in, PrintStream out) {
        this.config = config;
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
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
            out.println("KoraDB CLI v0.1.0-SNAPSHOT");
            return 0;
        }

        try (KoraClient client = KoraClient.connect(config.host(), config.port(), config.database())) {
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
        out.println(BANNER);
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

            // Accumulate query
            if (buffer.isEmpty()) {
                buffer.append(line);
            } else {
                buffer.append(" ").append(line);
            }

            // Check if query is terminated by semicolon
            if (trimmed.endsWith(";")) {
                String sql = buffer.toString().trim();
                buffer.setLength(0);
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
            }
            default -> {
                out.println("Unknown command: " + meta + ". Type \\h for help.");
            }
        }
        return false;
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
                  -v, --version          Print version information and exit
                  --help                 Show this help message and exit
                """);
    }
}
