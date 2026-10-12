package dev.koradb.jdbc;

import java.net.URI;
import java.sql.SQLException;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser and container for KoraDB JDBC connection URLs.
 *
 * <p>Supported formats:
 * <ul>
 *   <li>{@code jdbc:koradb://localhost:8765/shop}</li>
 *   <li>{@code jdbc:koradb://127.0.0.1:8765}</li>
 *   <li>{@code jdbc:koradb://localhost:8765/shop?autoCommit=false&timeout=5000}</li>
 *   <li>{@code jdbc:koradb:shop}</li>
 * </ul>
 */
public final class KoraJdbcUrl {

    public static final String PREFIX = "jdbc:koradb:";
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8765;

    private static final Pattern URL_PATTERN = Pattern.compile(
            "^jdbc:koradb:(?://([^:/?#]+)(?::(\\d+))?(?:/([^?#]*))?)?(?:\\?(.*))?$"
    );

    private final String host;
    private final int port;
    private final String database;
    private final Properties properties;

    public KoraJdbcUrl(String host, int port, String database, Properties properties) {
        this.host = host != null && !host.isBlank() ? host.trim() : DEFAULT_HOST;
        this.port = port > 0 ? port : DEFAULT_PORT;
        this.database = database != null ? database.trim() : "";
        this.properties = properties != null ? new Properties(properties) : new Properties();
    }

    public static boolean accepts(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    public static KoraJdbcUrl parse(String url, Properties info) throws SQLException {
        if (!accepts(url)) {
            throw new SQLException("Invalid KoraDB JDBC URL: " + url);
        }

        Properties props = new Properties();
        if (info != null) {
            props.putAll(info);
        }

        Matcher matcher = URL_PATTERN.matcher(url);
        if (!matcher.matches()) {
            // Try simplified format: jdbc:koradb:<dbname>
            String suffix = url.substring(PREFIX.length()).trim();
            if (!suffix.startsWith("//") && !suffix.isEmpty()) {
                String db = suffix;
                int queryIdx = db.indexOf('?');
                if (queryIdx >= 0) {
                    parseQueryString(db.substring(queryIdx + 1), props);
                    db = db.substring(0, queryIdx);
                }
                return new KoraJdbcUrl(DEFAULT_HOST, DEFAULT_PORT, db, props);
            }
            throw new SQLException("Malformed KoraDB JDBC URL: " + url);
        }

        String hostGroup = matcher.group(1);
        String portGroup = matcher.group(2);
        String dbGroup = matcher.group(3);
        String queryGroup = matcher.group(4);

        String host = hostGroup != null && !hostGroup.isBlank() ? hostGroup : DEFAULT_HOST;
        int port = DEFAULT_PORT;
        if (portGroup != null && !portGroup.isBlank()) {
            try {
                port = Integer.parseInt(portGroup);
            } catch (NumberFormatException e) {
                throw new SQLException("Invalid port number in JDBC URL: " + portGroup);
            }
        }

        String database = dbGroup != null ? dbGroup : "";

        if (queryGroup != null && !queryGroup.isBlank()) {
            parseQueryString(queryGroup, props);
        }

        return new KoraJdbcUrl(host, port, database, props);
    }

    private static void parseQueryString(String query, Properties props) {
        for (String pair : query.split("&")) {
            if (pair.isBlank()) continue;
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq).trim();
                String val = pair.substring(eq + 1).trim();
                props.setProperty(key, val);
            } else {
                props.setProperty(pair.trim(), "true");
            }
        }
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String database() {
        return database;
    }

    public Properties properties() {
        return properties;
    }

    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public boolean getBooleanProperty(String key, boolean defaultValue) {
        String val = properties.getProperty(key);
        if (val == null) return defaultValue;
        return Boolean.parseBoolean(val);
    }
}
