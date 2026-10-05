package dev.koradb.cli;

/**
 * Entry point for the KoraDB CLI executable.
 */
public final class Main {

    public static void main(String[] args) {
        CliConfig config = CliConfig.parse(args);
        KoraCli cli = new KoraCli(config, System.in, System.out);
        int exitCode = cli.start();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
