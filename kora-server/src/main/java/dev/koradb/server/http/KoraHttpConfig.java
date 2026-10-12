package dev.koradb.server.http;

import java.util.Objects;

/**
 * Configuration settings for the KoraDB HTTP REST query server.
 */
public record KoraHttpConfig(
        String host,
        int port,
        boolean corsEnabled,
        boolean readOnly
) {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8080;

    public KoraHttpConfig {
        Objects.requireNonNull(host, "host must not be null");
    }

    public static KoraHttpConfig of(String host, int port) {
        return new KoraHttpConfig(host, port, true, false);
    }

    public static KoraHttpConfig of(String host, int port, boolean readOnly) {
        return new KoraHttpConfig(host, port, true, readOnly);
    }
}
