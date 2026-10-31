package dev.koradb.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Standard JDBC Driver implementation for KoraDB.
 *
 * <h2>Usage with DriverManager</h2>
 * <pre>{@code
 *   Connection conn = DriverManager.getConnection("jdbc:koradb://localhost:8765/shop");
 * }</pre>
 *
 * <h2>Usage with Spring Boot (application.properties)</h2>
 * <pre>{@code
 *   spring.datasource.url=jdbc:koradb://localhost:8765/shop
 *   spring.datasource.driver-class-name=dev.koradb.jdbc.KoraDriver
 * }</pre>
 */
public class KoraDriver implements Driver {

    public static final int MAJOR_VERSION = 2;
    public static final int MINOR_VERSION = 0;

    static {
        try {
            DriverManager.registerDriver(new KoraDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }

        KoraJdbcUrl jdbcUrl = KoraJdbcUrl.parse(url, info);
        return new KoraConnection(jdbcUrl);
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return KoraJdbcUrl.accepts(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        DriverPropertyInfo userProp = new DriverPropertyInfo("user", info != null ? info.getProperty("user", "") : "");
        userProp.description = "Database user name";

        DriverPropertyInfo passProp = new DriverPropertyInfo("password", info != null ? info.getProperty("password", "") : "");
        passProp.description = "Database password";

        DriverPropertyInfo autoCommitProp = new DriverPropertyInfo("autoCommit", info != null ? info.getProperty("autoCommit", "true") : "true");
        autoCommitProp.description = "Enable autocommit mode by default";

        return new DriverPropertyInfo[]{userProp, passProp, autoCommitProp};
    }

    @Override
    public int getMajorVersion() {
        return MAJOR_VERSION;
    }

    @Override
    public int getMinorVersion() {
        return MINOR_VERSION;
    }

    @Override
    public boolean jdbcCompliant() {
        // Full JDBC compliance requires enterprise features (e.g. CallableStatement, XA)
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return Logger.getLogger("dev.koradb.jdbc");
    }
}
