package dev.koradb.jdbc;

import dev.koradb.client.ClientQueryResult;
import dev.koradb.client.KoraClient;
import dev.koradb.client.KoraClientException;

import java.io.IOException;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * JDBC Connection implementation for KoraDB.
 */
public class KoraConnection implements Connection {

    private final KoraJdbcUrl jdbcUrl;
    private final KoraClient client;
    private boolean autoCommit;
    private boolean inTransaction = false;
    /** True after the server rolled the open transaction back on its own (write conflict / deadlock). */
    private boolean serverAborted = false;
    /** Server sessions default to SNAPSHOT isolation, reported to JDBC as REPEATABLE READ. */
    private int isolationLevel = Connection.TRANSACTION_REPEATABLE_READ;
    private boolean closed = false;
    private String currentCatalog;
    private boolean readOnly = false;

    public KoraConnection(KoraJdbcUrl jdbcUrl) throws SQLException {
        this.jdbcUrl = jdbcUrl;
        this.autoCommit = jdbcUrl.getBooleanProperty("autoCommit", true);
        this.currentCatalog = jdbcUrl.database();

        // DriverManager.getConnection(url, user, password) and ?user=&password= both land here
        String user = jdbcUrl.getProperty("user", "");
        String password = jdbcUrl.getProperty("password", "");
        try {
            KoraClient clientInstance;
            try {
                clientInstance = KoraClient.connect(jdbcUrl.host(), jdbcUrl.port(), jdbcUrl.database(), user, password);
            } catch (KoraClientException e) {
                if (e.getMessage() != null && e.getMessage().contains("does not exist") && !jdbcUrl.database().isEmpty()) {
                    // Connect without database, create it, and use it
                    clientInstance = KoraClient.connect(jdbcUrl.host(), jdbcUrl.port(), "", user, password);
                    clientInstance.execute("CREATE DATABASE " + jdbcUrl.database() + ";");
                    clientInstance.execute("USE " + jdbcUrl.database() + ";");
                } else {
                    throw e;
                }
            }
            this.client = clientInstance;
            if (!this.currentCatalog.isEmpty()) {
                try {
                    this.client.execute("USE " + this.currentCatalog + ";");
                } catch (Exception ignored) {
                }
            }
        } catch (KoraClientException e) {
            if (e.getErrorCode() == dev.koradb.protocol.ErrorMessage.ERR_AUTH) {
                // SQLState 28000: invalid authorization specification
                throw new SQLException(e.getMessage(), "28000", e);
            }
            throw new SQLException("Failed to connect to KoraDB server at " + jdbcUrl.host() + ":" + jdbcUrl.port(), e);
        } catch (IOException e) {
            throw new SQLException("Failed to connect to KoraDB server at " + jdbcUrl.host() + ":" + jdbcUrl.port(), e);
        }
    }

    public KoraClient getClient() {
        return client;
    }

    public ClientQueryResult executeRaw(String sql) throws SQLException {
        checkClosed();
        try {
            // Manage implicit transaction start when autocommit is false
            String trimmed = sql.trim().toUpperCase();
            if (!autoCommit && !inTransaction && !trimmed.startsWith("BEGIN") && !trimmed.startsWith("COMMIT")
                    && !trimmed.startsWith("ROLLBACK") && !trimmed.startsWith("SET ")
                    && !trimmed.startsWith("CREATE ") && !trimmed.startsWith("DROP ") && !trimmed.startsWith("ALTER ")
                    && !trimmed.startsWith("USE ")) {
                // DDL is not transactional on the server, so it is run outside a transaction
                client.execute("BEGIN;");
                inTransaction = true;
                serverAborted = false;
            }

            ClientQueryResult res;
            try {
                res = client.execute(sql);
            } catch (KoraClientException e) {
                // The server rolls the transaction back itself on a write conflict or deadlock.
                String m = String.valueOf(e.getMessage()).toLowerCase();
                if (inTransaction && (m.contains("conflict") || m.contains("deadlock"))) {
                    serverAborted = true;
                }
                throw e;
            }

            if (trimmed.startsWith("COMMIT") || trimmed.startsWith("ROLLBACK")) {
                inTransaction = false;
            }

            return res;
        } catch (KoraClientException e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    private void checkClosed() throws SQLException {
        if (closed || client.isClosed()) {
            throw new SQLException("Connection is closed");
        }
    }

    @Override
    public Statement createStatement() throws SQLException {
        checkClosed();
        return new KoraStatement(this);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkClosed();
        return new KoraPreparedStatement(this, sql);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException("Stored procedures are not supported in KoraDB");
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        checkClosed();
        return sql;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkClosed();
        if (this.autoCommit == autoCommit) return;

        if (this.inTransaction && autoCommit) {
            commit();
        }
        this.autoCommit = autoCommit;
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        checkClosed();
        return autoCommit;
    }

    @Override
    public void commit() throws SQLException {
        checkClosed();
        if (autoCommit) {
            throw new SQLException("Cannot commit when autoCommit is enabled");
        }
        if (inTransaction) {
            if (serverAborted) {
                inTransaction = false;
                serverAborted = false;
                throw new SQLException("Cannot commit: the server already rolled this transaction back "
                        + "after a write conflict or deadlock. Retry the transaction.", "40001");
            }
            try {
                client.execute("COMMIT;");
            } catch (KoraClientException e) {
                throw new SQLException("Failed to commit transaction: " + e.getMessage(), e);
            } finally {
                inTransaction = false;
            }
        }
    }

    @Override
    public void rollback() throws SQLException {
        checkClosed();
        if (autoCommit) {
            throw new SQLException("Cannot rollback when autoCommit is enabled");
        }
        if (inTransaction) {
            try {
                if (!serverAborted) {
                    client.execute("ROLLBACK;");
                }
            } catch (KoraClientException e) {
                // Already rolled back on the server (e.g. after a conflict): rollback is a no-op.
                if (!String.valueOf(e.getMessage()).contains("No active transaction")) {
                    throw new SQLException("Failed to rollback transaction: " + e.getMessage(), e);
                }
            } finally {
                inTransaction = false;
                serverAborted = false;
            }
        }
    }

    @Override
    public void close() throws SQLException {
        if (!closed) {
            try {
                if (inTransaction) {
                    client.execute("ROLLBACK;");
                }
            } catch (Exception ignored) {
            } finally {
                client.close();
                closed = true;
            }
        }
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed || client.isClosed();
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkClosed();
        return new KoraDatabaseMetaData(this, jdbcUrl);
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        checkClosed();
        this.readOnly = readOnly;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        checkClosed();
        return readOnly;
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        checkClosed();
        executeRaw("USE " + catalog + ";");
        this.currentCatalog = catalog;
    }

    @Override
    public String getCatalog() throws SQLException {
        checkClosed();
        return currentCatalog;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        checkClosed();
        // READ UNCOMMITTED is upgraded to READ COMMITTED (allowed by JDBC); REPEATABLE READ maps to
        // the server's SNAPSHOT isolation, which is also its default for client connections.
        String levelStr = switch (level) {
            case Connection.TRANSACTION_READ_UNCOMMITTED, Connection.TRANSACTION_READ_COMMITTED -> "READ_COMMITTED";
            case Connection.TRANSACTION_REPEATABLE_READ -> "SNAPSHOT";
            case Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
            default -> throw new SQLException("Unsupported isolation level: " + level);
        };
        executeRaw("SET TRANSACTION ISOLATION LEVEL " + levelStr + ";");
        isolationLevel = level == Connection.TRANSACTION_READ_UNCOMMITTED ? Connection.TRANSACTION_READ_COMMITTED : level;
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        checkClosed();
        return isolationLevel;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkClosed();
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkClosed();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepareCall(sql);
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return Map.of();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
    }

    @Override
    public int getHoldability() throws SQLException {
        return 1;
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        throw new SQLFeatureNotSupportedException("Savepoints are not supported in KoraDB");
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        throw new SQLFeatureNotSupportedException("Savepoints are not supported in KoraDB");
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException("Savepoints are not supported in KoraDB");
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException("Savepoints are not supported in KoraDB");
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return prepareCall(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public Clob createClob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Blob createBlob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public NClob createNClob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        return !closed && !client.isClosed();
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        return null;
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        return new Properties();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        setCatalog(schema);
    }

    @Override
    public String getSchema() throws SQLException {
        return getCatalog();
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        close();
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return 30000;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Cannot unwrap to " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }
}
