package dev.koradb.server;

import dev.koradb.replication.model.NodeRole;
import dev.koradb.replication.model.ReplicationMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.UUID;

/**
 * 12-factor configuration for launching a containerized or standalone KoraDB instance.
 * Supports environment variables with CLI flag overrides.
 */
public record ServerBootstrapConfig(
        String host,
        int port,
        Path dataDir,
        NodeRole role,
        int replicationPort,
        ReplicationMode replicationMode,
        String primaryHost,
        int primaryPort,
        String replicaId,
        boolean metricsEnabled,
        int metricsPort,
        boolean httpEnabled,
        int httpPort,
        Credentials credentials
) {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final String DEFAULT_CONTAINER_HOST = "0.0.0.0";
    public static final int DEFAULT_PORT = 8765;
    public static final int DEFAULT_REPLICATION_PORT = 8766;
    public static final int DEFAULT_METRICS_PORT = 9100;
    public static final int DEFAULT_HTTP_PORT = 8080;

    public ServerBootstrapConfig {
        Objects.requireNonNull(host, "host must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(replicationMode, "replicationMode must not be null");
        Objects.requireNonNull(primaryHost, "primaryHost must not be null");
        Objects.requireNonNull(replicaId, "replicaId must not be null");
        if (credentials == null) credentials = Credentials.NONE;
    }

    /** Constructor without credentials (authentication off). */
    public ServerBootstrapConfig(
            String host,
            int port,
            Path dataDir,
            NodeRole role,
            int replicationPort,
            ReplicationMode replicationMode,
            String primaryHost,
            int primaryPort,
            String replicaId,
            boolean metricsEnabled,
            int metricsPort,
            boolean httpEnabled,
            int httpPort
    ) {
        this(host, port, dataDir, role, replicationPort, replicationMode, primaryHost, primaryPort, replicaId,
                metricsEnabled, metricsPort, httpEnabled, httpPort, Credentials.NONE);
    }

    public ServerBootstrapConfig withCredentials(Credentials credentials) {
        return new ServerBootstrapConfig(host, port, dataDir, role, replicationPort, replicationMode, primaryHost,
                primaryPort, replicaId, metricsEnabled, metricsPort, httpEnabled, httpPort, credentials);
    }

    /** True when the SQL, HTTP or replication listeners accept connections from other machines. */
    public boolean bindsBeyondLoopback() {
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        return !(h.equals("localhost") || h.startsWith("127.") || h.equals("::1") || h.equals("[::1]"));
    }

    /**
     * Backward-compatible constructor defaulting HTTP REST API to enabled on port 8080.
     */
    public ServerBootstrapConfig(
            String host,
            int port,
            Path dataDir,
            NodeRole role,
            int replicationPort,
            ReplicationMode replicationMode,
            String primaryHost,
            int primaryPort,
            String replicaId,
            boolean metricsEnabled,
            int metricsPort
    ) {
        this(host, port, dataDir, role, replicationPort, replicationMode, primaryHost, primaryPort, replicaId, metricsEnabled, metricsPort, true, DEFAULT_HTTP_PORT);
    }

    /**
     * Resolves configuration from environment variables, falling back to sensible defaults,
     * and applies any command-line argument overrides.
     */
    public static ServerBootstrapConfig fromEnvAndArgs(String[] args) {
        // 1. Initial defaults from environment
        boolean isContainer = isContainerEnvironment();
        String host = getEnvOrDefault("KORA_HOST", isContainer ? DEFAULT_CONTAINER_HOST : DEFAULT_HOST);
        int port = getEnvIntOrDefault("KORA_PORT", DEFAULT_PORT);

        String dataDirEnv = System.getenv("KORA_DATA_DIR");
        Path dataDir = (dataDirEnv != null && !dataDirEnv.isBlank()) ? Paths.get(dataDirEnv) : null;

        String roleEnv = getEnvOrDefault("KORA_ROLE", "PRIMARY");
        NodeRole role = parseRole(roleEnv);

        int replicationPort = getEnvIntOrDefault("KORA_REPLICATION_PORT", DEFAULT_REPLICATION_PORT);
        String modeEnv = getEnvOrDefault("KORA_REPLICATION_MODE", "ASYNC");
        ReplicationMode replicationMode = "SYNC".equalsIgnoreCase(modeEnv) ? ReplicationMode.SYNC : ReplicationMode.ASYNC;

        String primaryHost = getEnvOrDefault("KORA_PRIMARY_HOST", "localhost");
        int primaryPort = getEnvIntOrDefault("KORA_PRIMARY_PORT", DEFAULT_REPLICATION_PORT);

        String replicaId = getEnvOrDefault("KORA_REPLICA_ID", generateDefaultReplicaId());

        String metricsEnv = System.getenv("KORA_METRICS_ENABLED");
        boolean metricsEnabled = metricsEnv == null || (!"false".equalsIgnoreCase(metricsEnv) && !"0".equals(metricsEnv));
        int metricsPort = getEnvIntOrDefault("KORA_METRICS_PORT", DEFAULT_METRICS_PORT);

        String httpEnv = System.getenv("KORA_HTTP_ENABLED");
        boolean httpEnabled = httpEnv == null || (!"false".equalsIgnoreCase(httpEnv) && !"0".equals(httpEnv));
        int httpPort = getEnvIntOrDefault("KORA_HTTP_PORT", DEFAULT_HTTP_PORT);

        String user = getEnvOrDefault("KORA_USER", Credentials.DEFAULT_USER);
        String password = System.getenv("KORA_PASSWORD");
        String passwordFile = System.getenv("KORA_PASSWORD_FILE");

        // 2. Parse CLI args to override
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "-h", "--host" -> {
                        if (++i < args.length) host = args[i];
                    }
                    case "-p", "--port" -> {
                        if (++i < args.length) port = Integer.parseInt(args[i]);
                    }
                    case "-d", "--data-dir" -> {
                        if (++i < args.length) dataDir = Paths.get(args[i]);
                    }
                    case "--role" -> {
                        if (++i < args.length) role = parseRole(args[i]);
                    }
                    case "--replication-port" -> {
                        if (++i < args.length) replicationPort = Integer.parseInt(args[i]);
                    }
                    case "--replication-mode" -> {
                        if (++i < args.length) {
                            replicationMode = "SYNC".equalsIgnoreCase(args[i]) ? ReplicationMode.SYNC : ReplicationMode.ASYNC;
                        }
                    }
                    case "--primary-host" -> {
                        if (++i < args.length) primaryHost = args[i];
                    }
                    case "--primary-port" -> {
                        if (++i < args.length) primaryPort = Integer.parseInt(args[i]);
                    }
                    case "--replica-id" -> {
                        if (++i < args.length) replicaId = args[i];
                    }
                    case "--metrics-port" -> {
                        if (++i < args.length) metricsPort = Integer.parseInt(args[i]);
                    }
                    case "--no-metrics" -> metricsEnabled = false;
                    case "--metrics" -> metricsEnabled = true;
                    case "--http-port" -> {
                        if (++i < args.length) httpPort = Integer.parseInt(args[i]);
                    }
                    case "--no-http" -> httpEnabled = false;
                    case "--http" -> httpEnabled = true;
                    case "--user" -> {
                        if (++i < args.length) user = args[i];
                    }
                    case "--password" -> {
                        if (++i < args.length) password = args[i];
                    }
                    case "--password-file" -> {
                        if (++i < args.length) passwordFile = args[i];
                    }
                    case "--help", "-v", "--version" -> {
                        // Handled by main() caller
                    }
                    default -> {
                        if (!arg.startsWith("-")) {
                            // ignore or treat as unknown
                        }
                    }
                }
            }
        }

        return new ServerBootstrapConfig(
                host,
                port,
                dataDir,
                role,
                replicationPort,
                replicationMode,
                primaryHost,
                primaryPort,
                replicaId,
                metricsEnabled,
                metricsPort,
                httpEnabled,
                httpPort,
                new Credentials(user, resolvePassword(password, passwordFile))
        );
    }

    /** A password file (Docker / Kubernetes secret) wins over a literal password; trailing newlines are dropped. */
    private static String resolvePassword(String password, String passwordFile) {
        if (passwordFile != null && !passwordFile.isBlank()) {
            try {
                return Files.readString(Path.of(passwordFile.trim())).strip();
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("Cannot read password file '" + passwordFile + "': " + e.getMessage(), e);
            }
        }
        return password != null ? password : "";
    }

    @Override
    public String toString() {
        return "ServerBootstrapConfig[host=" + host + ", port=" + port + ", dataDir=" + dataDir + ", role=" + role
                + ", replicationPort=" + replicationPort + ", replicationMode=" + replicationMode
                + ", primary=" + primaryHost + ":" + primaryPort + ", replicaId=" + replicaId
                + ", metrics=" + (metricsEnabled ? metricsPort : "off") + ", http=" + (httpEnabled ? httpPort : "off")
                + ", " + credentials + "]";
    }

    private static NodeRole parseRole(String roleStr) {
        if (roleStr == null || roleStr.isBlank()) {
            return NodeRole.PRIMARY;
        }
        return switch (roleStr.trim().toUpperCase()) {
            case "REPLICA", "FOLLOWER", "READONLY" -> NodeRole.REPLICA;
            case "STANDALONE", "SINGLE" -> NodeRole.STANDALONE;
            default -> NodeRole.PRIMARY;
        };
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val.trim() : defaultValue;
    }

    private static int getEnvIntOrDefault(String key, int defaultValue) {
        String val = System.getenv(key);
        if (val != null && !val.isBlank()) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private static boolean isContainerEnvironment() {
        return System.getenv("KUBERNETES_SERVICE_HOST") != null
                || Files.exists(Path.of("/.dockerenv"));
    }

    private static String generateDefaultReplicaId() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return hostname.trim();
        }
        return "replica-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
