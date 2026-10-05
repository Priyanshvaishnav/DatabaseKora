package dev.koradb.server;

import java.nio.file.Path;

/**
 * Configuration options for {@link KoraServer}.
 *
 * @param host          the network interface to bind (e.g. "127.0.0.1" or "0.0.0.0")
 * @param port          the TCP port to listen on (0 for ephemeral)
 * @param backlog       connection backlog queue size
 * @param dataDirectory optional path to persistent data directory; null for in-memory mode
 */
public record KoraServerConfig(
        String host,
        int port,
        int backlog,
        Path dataDirectory
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
    }

    public static KoraServerConfig inMemory(int port) {
        return new KoraServerConfig(DEFAULT_HOST, port, DEFAULT_BACKLOG, null);
    }

    public static KoraServerConfig persistent(int port, Path dataDirectory) {
        return new KoraServerConfig(DEFAULT_HOST, port, DEFAULT_BACKLOG, dataDirectory);
    }

    public static KoraServerConfig defaultConfig() {
        return inMemory(DEFAULT_PORT);
    }
}
