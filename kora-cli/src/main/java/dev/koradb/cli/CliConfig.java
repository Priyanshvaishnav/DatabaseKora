package dev.koradb.cli;

/**
 * Command-line configuration parsed from arguments.
 */
public record CliConfig(
        String host,
        int port,
        String database,
        String executeSql,
        boolean help,
        boolean version
) {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8765;

    public static CliConfig parse(String[] args) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        String database = "";
        String executeSql = null;
        boolean help = false;
        boolean version = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h", "--host" -> {
                    if (++i < args.length) host = args[i];
                }
                case "-p", "--port" -> {
                    if (++i < args.length) port = Integer.parseInt(args[i]);
                }
                case "-d", "--database" -> {
                    if (++i < args.length) database = args[i];
                }
                case "-e", "--execute" -> {
                    if (++i < args.length) executeSql = args[i];
                }
                case "--help" -> help = true;
                case "-v", "--version" -> version = true;
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    help = true;
                }
            }
        }

        return new CliConfig(host, port, database, executeSql, help, version);
    }
}
