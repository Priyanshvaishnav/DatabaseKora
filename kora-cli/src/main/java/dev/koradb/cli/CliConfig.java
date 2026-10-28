package dev.koradb.cli;

/**
 * Command-line configuration parsed from arguments.
 *
 * @param user     user name for servers started with a password ({@code -u}, env {@code KORA_USER})
 * @param password password ({@code --password}, env {@code KORA_PASSWORD}); empty if none
 */
public record CliConfig(
        String host,
        int port,
        String database,
        String executeSql,
        boolean help,
        boolean version,
        String user,
        String password
) {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8765;

    public CliConfig(String host, int port, String database, String executeSql, boolean help, boolean version) {
        this(host, port, database, executeSql, help, version, "", "");
    }

    public static CliConfig parse(String[] args) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        String database = "";
        String executeSql = null;
        boolean help = false;
        boolean version = false;
        String user = envOr("KORA_USER", "");
        String password = envOr("KORA_PASSWORD", "");

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
                case "-u", "--user" -> {
                    if (++i < args.length) user = args[i];
                }
                case "--password" -> {
                    if (++i < args.length) password = args[i];
                }
                case "--help" -> help = true;
                case "-v", "--version" -> version = true;
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    help = true;
                }
            }
        }

        return new CliConfig(host, port, database, executeSql, help, version, user, password);
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return v != null ? v : fallback;
    }

    @Override
    public String toString() {
        return "CliConfig[host=" + host + ", port=" + port + ", database=" + database + ", user=" + user
                + ", password=" + (password.isEmpty() ? "" : "****") + "]";
    }
}
