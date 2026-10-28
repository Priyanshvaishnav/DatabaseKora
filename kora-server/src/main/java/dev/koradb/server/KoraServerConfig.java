package dev.koradb.server;

import java.nio.file.Path;

/**
 * Configuration options for {@link KoraServer}.
 *
 * @param host          the network interface to bind (e.g. "127.0.0.1" or "0.0.0.0")
 * @param port          the TCP port to listen on (0 for ephemeral)
 * @param backlog       connection backlog queue size
 * @param dataDirectory optional path to persistent data directory; null for in-memory mode
 * @param readOnly      whether this server instance rejects mutating statements (read replica mode)
 * @param credentials   user name and password clients must present ({@link Credentials#NONE}: no authentication)
 */
public record KoraServerConfig(
        String host,
        int port,
        int backlog,
        Path dataDirectory,
        boolean readOnly,
        Credentials credentials
) {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8765;
    public static final int DEFAULT_BACKLOG = 128;

    public KoraServerConfig {
        if (host == null || host.isBlank()) host = DEFAULT_HOST;
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        if (backlog <= 0) backlog = DEFAULT_BACKLOG;
        if (credentials == null) credentials = Credentials.NONE;
    }

    public KoraServerConfig(String host, int port, int backlog, Path dataDirectory, boolean readOnly) {
        this(host, port, backlog, dataDirectory, readOnly, Credentials.NONE);
    }

    public KoraServerConfig(String host, int port, int backlog, Path dataDirectory) {
        this(host, port, backlog, dataDirectory, false);
    }

    public KoraServerConfig withCredentials(Credentials credentials) {
        return new KoraServerConfig(host, port, backlog, dataDirectory, readOnly, credentials);
    }

    public static KoraServerConfig inMemory(int port) {
        return new KoraServerConfig(DEFAULT_HOST, port, DEFAULT_BACKLOG, null, false);
    }

    public static KoraServerConfig persistent(int port, Path dataDirectory) {
        return new KoraServerConfig(DEFAULT_HOST, port, DEFAULT_BACKLOG, dataDirectory, false);
    }

    public static KoraServerConfig readOnly(String host, int port, Path dataDirectory) {
        return new KoraServerConfig(host, port, DEFAULT_BACKLOG, dataDirectory, true);
    }

    public static KoraServerConfig defaultConfig() {
        return inMemory(DEFAULT_PORT);
    }
}
