package dev.koradb.cli;

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
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("KoraDB CLI v0.1.0-SNAPSHOT");
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
}
