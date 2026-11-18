package dev.koradb.cli;

import dev.koradb.ai.engine.KoraAiEngine;
import dev.koradb.ai.provider.LlmClient;
import dev.koradb.ai.provider.LlmRequest;
import dev.koradb.ai.provider.LlmResponse;
import dev.koradb.server.KoraServer;
import dev.koradb.server.KoraServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class KoraCliTest {

    private KoraServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new KoraServer(KoraServerConfig.inMemory(0));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Should display version information with -v")
    void testVersion() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CliConfig config = CliConfig.parse(new String[]{"-v"});
        KoraCli cli = new KoraCli(config, new ByteArrayInputStream(new byte[0]), new PrintStream(out));

        int exitCode = cli.start();
        assertThat(exitCode).isEqualTo(0);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("KoraDB CLI v2.0.1");
    }

    @Test
    @DisplayName("Should display help manual with --help")
    void testHelp() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CliConfig config = CliConfig.parse(new String[]{"--help"});
        KoraCli cli = new KoraCli(config, new ByteArrayInputStream(new byte[0]), new PrintStream(out));

        int exitCode = cli.start();
        assertThat(exitCode).isEqualTo(0);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Usage: koradb-cli [OPTIONS]");
    }

    @Test
    @DisplayName("Should execute one-shot query with -e and print formatted results")
    void testOneShotExecution() {
        int port = server.getPort();

        // 1. Setup table
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CliConfig setupConfig = CliConfig.parse(new String[]{
                "--port", String.valueOf(port),
                "-e", "CREATE DATABASE store;"
        });
        new KoraCli(setupConfig, new ByteArrayInputStream(new byte[0]), new PrintStream(out)).start();

        // 2. Query
        out.reset();
        CliConfig queryConfig = CliConfig.parse(new String[]{
                "--port", String.valueOf(port),
                "-d", "store",
                "-e", "SHOW DATABASES;"
        });
        int exitCode = new KoraCli(queryConfig, new ByteArrayInputStream(new byte[0]), new PrintStream(out)).start();

        assertThat(exitCode).isEqualTo(0);
        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("store");
    }

    @Test
    @DisplayName("Should run interactive REPL handling multi-line queries and meta-commands")
    void testInteractiveRepl() {
        int port = server.getPort();

        String inputScript = """
                CREATE DATABASE demo;
                USE demo;
                CREATE TABLE products (
                    id INT,
                    name VARCHAR,
                    price DOUBLE
                );
                INSERT INTO products VALUES (1, 'Widget', 19.99);
                INSERT INTO products VALUES (2, 'Gadget', 49.99);
                SELECT * FROM products WHERE price > 20.0;
                \\dt
                \\d products
                \\status
                \\q
                """;

        ByteArrayInputStream in = new ByteArrayInputStream(inputScript.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        CliConfig config = CliConfig.parse(new String[]{"--port", String.valueOf(port)});
        KoraCli cli = new KoraCli(config, in, new PrintStream(out));

        int exitCode = cli.start();
        assertThat(exitCode).isEqualTo(0);

        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("Welcome to KoraDB CLI");
        assertThat(output).contains("Gadget");
        assertThat(output).doesNotContain("Widget"); // filtered out by WHERE price > 20.0
        assertThat(output).contains("products"); // \dt table listing
        assertThat(output).contains("Connection:  127.0.0.1:" + port); // \status
        assertThat(output).contains("Bye");
    }

    @Test
    @DisplayName("Should support AI CLI meta-commands (\\ai, \\explain-ai, \\advisor, \\ai-provider)")
    void testAiReplCommands() {
        int port = server.getPort();

        String inputScript = """
                CREATE DATABASE shop;
                USE shop;
                CREATE TABLE items (
                    id INT,
                    name VARCHAR,
                    price DOUBLE
                );
                INSERT INTO items VALUES (1, 'Keyboard', 75.0);
                INSERT INTO items VALUES (2, 'Mouse', 25.0);
                \\ai-provider
                \\ai-provider rule-based
                \\explain-ai SELECT * FROM items WHERE price > 50;
                \\advisor SELECT * FROM items WHERE price > 50;
                \\ai get all items where price > 50
                y
                \\q
                """;

        ByteArrayInputStream in = new ByteArrayInputStream(inputScript.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        CliConfig config = CliConfig.parse(new String[]{"--port", String.valueOf(port)});
        KoraCli cli = new KoraCli(config, in, new PrintStream(out));

        int exitCode = cli.start();
        assertThat(exitCode).isEqualTo(0);

        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("Active AI Provider: rule-based");
        assertThat(output).contains("Switched AI provider to: rule-based");
        assertThat(output).contains("Generated SQL: SELECT * FROM items WHERE price > 50;");
        assertThat(output).contains("Keyboard");
        assertThat(output).doesNotContain("Mouse");
        assertThat(output).contains("CREATE INDEX idx_items_price ON items (price);");
    }

    private static final String SHOP = """
            CREATE DATABASE shop;
            USE shop;
            CREATE TABLE orders (id INT PRIMARY KEY, amount DOUBLE, status VARCHAR(20));
            INSERT INTO orders VALUES (1, 250.0, 'pending'), (2, 90.5, 'shipped'), (3, 400.0, 'pending');
            """;

    /** Runs {@code script} in the REPL, optionally with {@code llm} as the AI provider, and returns the output. */
    private String repl(String script, LlmClient llm) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        KoraCli cli = new KoraCli(CliConfig.parse(new String[]{"--port", String.valueOf(server.getPort())}),
                new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)), new PrintStream(out));
        if (llm != null) cli.setAiEngine(new KoraAiEngine(llm));
        assertThat(cli.start()).isZero();
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("\\ai: a change the prompt asks for runs only after typing yes; Enter cancels it")
    void aiChangesNeedYes() {
        String output = repl(SHOP + """
                \\ai delete all orders

                SELECT COUNT(*) FROM orders;
                \\ai delete all orders
                yes
                SELECT COUNT(*) AS left_over FROM orders;
                \\q
                """, null);

        assertThat(output).contains("Generated SQL: DELETE FROM orders;");
        assertThat(output).contains("This DELETE changes data. Type yes to run it:");
        assertThat(output).contains("Execution cancelled.");
        assertThat(output).containsPattern("COUNT\\(\\*\\)[\\s|+-]*3");     // still there after Enter
        assertThat(output).containsPattern("left_over[\\s|+-]*0");          // gone after yes
    }

    @Test
    @DisplayName("\\ai: a change the prompt does not ask for is never run, even when confirmed")
    void aiMismatchedChangeIsRefused() {
        LlmClient injected = new LlmClient() {
            @Override public String getProviderName() { return "test"; }
            @Override public String getModelName() { return "always-delete"; }
            @Override public LlmResponse complete(LlmRequest request) {
                return LlmResponse.of("DELETE FROM orders;", "always-delete", "test");
            }
        };
        String output = repl(SHOP + """
                \\ai how many orders are there
                SELECT COUNT(*) FROM orders;
                \\q
                """, injected);

        assertThat(output).contains("Generated SQL: DELETE FROM orders;");
        assertThat(output).contains("Not executed: your request does not ask for a DELETE");
        assertThat(output).doesNotContain("Type yes to run it").doesNotContain("Execution cancelled.");
        assertThat(output).containsPattern("COUNT\\(\\*\\)[\\s|+-]*3");
    }

    @Test
    @DisplayName("\\ai: column values reach the offline provider, so 'pending' becomes a filter")
    void aiUsesColumnValues() {
        String output = repl(SHOP + """
                \\ai total amount of pending orders

                \\q
                """, null);

        assertThat(output).contains("status = 'pending'");
        assertThat(output).contains("650.0");
    }

    @Test
    @DisplayName("\\ai: a translation that fails validation is shown but not run")
    void aiInvalidTranslationIsNotRun() {
        String output = repl(SHOP + """
                \\ai show me the weather in paris
                \\q
                """, null);

        assertThat(output).contains("Not executed:");
        assertThat(output).doesNotContain("Execute query?");
    }
}

