package dev.koradb.cli;

/**
 * Entry point for the KoraDB CLI executable.
 */
public final class Main {

    /**
     * Character set of the terminal: the console's own (e.g. cp437/cp850 on Windows, UTF-8 on most
     * Linux/macOS terminals), else the JVM's stdout encoding, else UTF-8.
     */
    static java.nio.charset.Charset consoleCharset() {
        try {
            java.io.Console console = System.console();
            if (console != null) return console.charset();
            String enc = System.getProperty("stdout.encoding");
            if (enc != null) return java.nio.charset.Charset.forName(enc);
        } catch (RuntimeException ignored) {
            // fall through
        }
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    public static void main(String[] args) {
        CliConfig config = CliConfig.parse(args);
        java.nio.charset.Charset cs = consoleCharset();
        java.io.PrintStream out = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, cs);
        KoraCli cli = new KoraCli(config, System.in, out, cs);
        int exitCode = cli.start();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
