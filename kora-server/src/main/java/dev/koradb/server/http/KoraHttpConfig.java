package dev.koradb.server.http;

import dev.koradb.server.Credentials;

import java.util.Objects;

/**
 * Configuration for {@link KoraHttpServer}.
 *
 * @param credentials user name and password required on every endpoint except health
 *                    ({@link Credentials#NONE}: no authentication)
 */
public record KoraHttpConfig(
        String host,
        int port,
        boolean corsEnabled,
        boolean readOnly,
        Credentials credentials
) {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8080;

    public KoraHttpConfig {
        Objects.requireNonNull(host, "host must not be null");
        if (credentials == null) credentials = Credentials.NONE;
    }

    public KoraHttpConfig(String host, int port, boolean corsEnabled, boolean readOnly) {
        this(host, port, corsEnabled, readOnly, Credentials.NONE);
    }

    public static KoraHttpConfig of(String host, int port) {
        return new KoraHttpConfig(host, port, true, false);
    }

    public static KoraHttpConfig of(String host, int port, boolean readOnly) {
        return new KoraHttpConfig(host, port, true, readOnly);
    }

    public KoraHttpConfig withCredentials(Credentials credentials) {
        return new KoraHttpConfig(host, port, corsEnabled, readOnly, credentials);
    }
}
