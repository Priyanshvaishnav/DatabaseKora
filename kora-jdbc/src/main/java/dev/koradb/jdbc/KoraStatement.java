package dev.koradb.jdbc;

import dev.koradb.client.ClientQueryResult;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;

/**
 * JDBC Statement implementation for KoraDB.
 */
public class KoraStatement implements Statement {

    protected final KoraConnection connection;
    protected ResultSet currentResultSet;
    protected int currentUpdateCount = -1;
    protected boolean closed = false;
    protected int maxRows = 0;
    protected int queryTimeout = 0;

    public KoraStatement(KoraConnection connection) {
        this.connection = connection;
    }

    protected void checkClosed() throws SQLException {
        if (closed || connection.isClosed()) {
            throw new SQLException("Statement is closed");
        }
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        checkClosed();
        closeCurrentResult();

        ClientQueryResult res = connection.executeRaw(sql);
        if (!res.isQuery()) {
            throw new SQLException("Query did not return a ResultSet: " + sql);
        }

        currentResultSet = new KoraResultSet(this, res);
        currentUpdateCount = -1;
        return currentResultSet;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        checkClosed();
        closeCurrentResult();

        ClientQueryResult res = connection.executeRaw(sql);
        currentResultSet = null;
        currentUpdateCount = res.affectedRows();
        return currentUpdateCount;
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        checkClosed();
        closeCurrentResult();

        ClientQueryResult res = connection.executeRaw(sql);
        if (res.isQuery()) {
            currentResultSet = new KoraResultSet(this, res);
            currentUpdateCount = -1;
            return true;
        } else {
            currentResultSet = null;
            currentUpdateCount = res.affectedRows();
            return false;
        }
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        checkClosed();
        return currentResultSet;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        checkClosed();
        return currentUpdateCount;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        checkClosed();
        closeCurrentResult();
        return false;
    }

    private void closeCurrentResult() throws SQLException {
        if (currentResultSet != null) {
            currentResultSet.close();
            currentResultSet = null;
        }
        currentUpdateCount = -1;
    }

    @Override
    public void close() throws SQLException {
        if (!closed) {
            closeCurrentResult();
            closed = true;
        }
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }

    @Override
    public Connection getConnection() throws SQLException {
        checkClosed();
        return connection;
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        return 0;
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
    }

    @Override
    public int getMaxRows() throws SQLException {
        return maxRows;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        this.maxRows = max;
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        return queryTimeout;
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        this.queryTimeout = seconds;
    }

    @Override
    public void cancel() throws SQLException {
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
    }

    @Override
    public void setCursorName(String name) throws SQLException {
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return ResultSet.FETCH_FORWARD;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
    }

    @Override
    public int getFetchSize() throws SQLException {
        return 0;
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getResultSetType() throws SQLException {
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        throw new SQLException("Batch execution is not supported in simple statements");
    }

    @Override
    public void clearBatch() throws SQLException {
    }

    @Override
    public int[] executeBatch() throws SQLException {
        return new int[0];
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return getMoreResults();
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        throw new SQLException("Generated keys are not supported");
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        return execute(sql);
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        return execute(sql);
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        return execute(sql);
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
    }

    @Override
    public boolean isPoolable() throws SQLException {
        return false;
    }

    @Override
    public void closeOnCompletion() throws SQLException {
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        return false;
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
