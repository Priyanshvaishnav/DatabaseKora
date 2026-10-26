package dev.koradb.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * JDBC PreparedStatement implementation for KoraDB.
 */
public class KoraPreparedStatement extends KoraStatement implements PreparedStatement {

    private final String rawSql;
    private final Map<Integer, Object> parameters = new HashMap<>();

    public KoraPreparedStatement(KoraConnection connection, String rawSql) {
        super(connection);
        this.rawSql = rawSql;
    }

    private String buildExecutableSql() throws SQLException {
        StringBuilder sb = new StringBuilder();
        int paramIndex = 1;
        boolean inSingleQuote = false;

        for (int i = 0; i < rawSql.length(); i++) {
            char c = rawSql.charAt(i);

            if (c == '\'') {
                inSingleQuote = !inSingleQuote;
                sb.append(c);
            } else if (c == '?' && !inSingleQuote) {
                if (!parameters.containsKey(paramIndex)) {
                    throw new SQLException("Parameter #" + paramIndex + " was not set");
                }
                Object val = parameters.get(paramIndex);
                sb.append(formatSqlLiteral(val));
                paramIndex++;
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    private String formatSqlLiteral(Object val) {
        if (val == null) {
            return "NULL";
        }
        if (val instanceof Number || val instanceof Boolean) {
            return val.toString();
        }
        // Dates/times: the server's TIMESTAMP format (second precision), not Timestamp.toString()
        if (val instanceof java.sql.Timestamp ts) {
            return "'" + ts.toLocalDateTime().withNano(0).toString().replace('T', ' ') + "'";
        }
        if (val instanceof java.time.LocalDateTime ldt) {
            return "'" + ldt.withNano(0).toString().replace('T', ' ') + "'";
        }
        if (val instanceof java.sql.Date d) {
            return "'" + d.toLocalDate() + " 00:00:00'";
        }
        if (val instanceof java.time.LocalDate ld) {
            return "'" + ld + " 00:00:00'";
        }
        // String / char / dates: quote and escape internal single quotes
        String str = val.toString().replace("'", "''");
        return "'" + str + "'";
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        return executeQuery(buildExecutableSql());
    }

    @Override
    public int executeUpdate() throws SQLException {
        return executeUpdate(buildExecutableSql());
    }

    @Override
    public boolean execute() throws SQLException {
        return execute(buildExecutableSql());
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        parameters.put(parameterIndex, null);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        parameters.put(parameterIndex, (int) x);
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        parameters.put(parameterIndex, (int) x);
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        parameters.put(parameterIndex, (double) x);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        parameters.put(parameterIndex, x != null ? x.toPlainString() : null);
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        parameters.put(parameterIndex, x != null ? new String(x) : null);
    }

    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        parameters.put(parameterIndex, x != null ? x.toString() : null);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void clearParameters() throws SQLException {
        parameters.clear();
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw new SQLException("Stream parameters not supported");
    }

    @Override
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw new SQLException("Stream parameters not supported");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw new SQLException("Stream parameters not supported");
    }

    @Override
    public void addBatch() throws SQLException {
        checkClosed();
        batch.add(buildExecutableSql());   // parameters are bound now, as the JDBC spec requires
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        throw new SQLException("Stream parameters not supported");
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        throw new SQLException("Ref not supported");
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        throw new SQLException("Blob not supported");
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        throw new SQLException("Clob not supported");
    }

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        throw new SQLException("Array not supported");
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return null;
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        setDate(parameterIndex, x);
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        setTime(parameterIndex, x);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        setTimestamp(parameterIndex, x);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        setNull(parameterIndex, sqlType);
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        setString(parameterIndex, x != null ? x.toString() : null);
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        throw new SQLException("Parameter metadata not available");
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        throw new SQLException("RowId not supported");
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        setString(parameterIndex, value);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        throw new SQLException("Stream parameters not supported");
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        throw new SQLException("NClob not supported");
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw new SQLException("Clob not supported");
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        throw new SQLException("Blob not supported");
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw new SQLException("NClob not supported");
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        throw new SQLException("SQLXML not supported");
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        throw new SQLException("Stream parameters not supported");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        throw new SQLException("Stream parameters not supported");
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        throw new SQLException("Stream parameters not supported");
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        throw new SQLException("Stream parameters not supported");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        throw new SQLException("Stream parameters not supported");
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        throw new SQLException("Stream parameters not supported");
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        throw new SQLException("Stream parameters not supported");
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        throw new SQLException("Clob not supported");
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        throw new SQLException("Blob not supported");
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        throw new SQLException("NClob not supported");
    }
}
