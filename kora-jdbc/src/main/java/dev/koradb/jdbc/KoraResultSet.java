package dev.koradb.jdbc;

import dev.koradb.client.ClientQueryResult;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * JDBC ResultSet implementation for KoraDB tabular query results.
 */
public class KoraResultSet implements ResultSet {

    private final Statement statement;
    private final ClientQueryResult result;
    private int cursor = -1;
    private boolean closed = false;
    private boolean wasNull = false;

    public KoraResultSet(Statement statement, ClientQueryResult result) {
        this.statement = statement;
        this.result = result;
    }

    private void checkOpen() throws SQLException {
        if (closed) {
            throw new SQLException("ResultSet is closed");
        }
    }

    private void checkRow() throws SQLException {
        checkOpen();
        if (cursor < 0) {
            throw new SQLException("Before start of result set (call next() first)");
        }
        if (cursor >= result.getRowCount()) {
            throw new SQLException("After end of result set");
        }
    }

    @Override
    public boolean next() throws SQLException {
        checkOpen();
        cursor++;
        return cursor < result.getRowCount();
    }

    @Override
    public void close() throws SQLException {
        closed = true;
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return wasNull;
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        checkRow();
        if (columnIndex < 1 || columnIndex > result.getColumnCount()) {
            throw new SQLException("Invalid column index: " + columnIndex);
        }

        List<Object> row = result.rows().get(cursor);
        int idx = columnIndex - 1;
        Object val = idx < row.size() ? row.get(idx) : null;
        wasNull = (val == null);
        return val;
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        Object val = getObject(columnIndex);
        return val != null ? val.toString() : null;
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        Object val = getObject(columnIndex);
        if (val == null) return false;
        if (val instanceof Boolean b) return b;
        if (val instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(val.toString());
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return (byte) getInt(columnIndex);
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return (short) getInt(columnIndex);
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        Object val = getObject(columnIndex);
        if (val == null) return 0;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (NumberFormatException e) {
            throw new SQLException("Cannot convert value to int: " + val, e);
        }
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        Object val = getObject(columnIndex);
        if (val == null) return 0L;
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(val.toString().trim());
        } catch (NumberFormatException e) {
            throw new SQLException("Cannot convert value to long: " + val, e);
        }
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return (float) getDouble(columnIndex);
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        Object val = getObject(columnIndex);
        if (val == null) return 0.0;
        if (val instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(val.toString().trim());
        } catch (NumberFormatException e) {
            throw new SQLException("Cannot convert value to double: " + val, e);
        }
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        return getBigDecimal(columnIndex);
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        Object val = getObject(columnIndex);
        if (val == null) return null;
        try {
            return new BigDecimal(val.toString());
        } catch (NumberFormatException e) {
            throw new SQLException("Cannot convert value to BigDecimal: " + val, e);
        }
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        Object val = getObject(columnIndex);
        if (val == null) return null;
        if (val instanceof Timestamp ts) return ts;
        if (val instanceof LocalDateTime ldt) return Timestamp.valueOf(ldt);
        try {
            return Timestamp.valueOf(val.toString());
        } catch (Exception e) {
            throw new SQLException("Cannot convert value to Timestamp: " + val, e);
        }
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        Timestamp ts = getTimestamp(columnIndex);
        return ts != null ? new Date(ts.getTime()) : null;
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        checkOpen();
        List<String> cols = result.columns();
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).equalsIgnoreCase(columnLabel)) {
                return i + 1;
            }
        }
        throw new SQLException("Column not found: " + columnLabel + " (available: " + cols + ")");
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        return new KoraResultSetMetaData(result);
    }

    @Override
    public int getRow() throws SQLException {
        checkOpen();
        if (cursor < 0 || cursor >= result.getRowCount()) {
            return 0;
        }
        return cursor + 1;
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkOpen();
        return cursor < 0 && result.getRowCount() > 0;
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        checkOpen();
        return cursor >= result.getRowCount() && result.getRowCount() > 0;
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkOpen();
        return cursor == 0 && result.getRowCount() > 0;
    }

    @Override
    public boolean isLast() throws SQLException {
        checkOpen();
        return cursor == result.getRowCount() - 1 && result.getRowCount() > 0;
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }

    @Override
    public Statement getStatement() throws SQLException {
        checkOpen();
        return statement;
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s != null ? s.getBytes() : null;
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return getBytes(findColumn(columnLabel));
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        Timestamp ts = getTimestamp(columnIndex);
        return ts != null ? new Time(ts.getTime()) : null;
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
    }

    @Override
    public int getType() throws SQLException {
        return TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        return CONCUR_READ_ONLY;
    }

    @Override
    public int getHoldability() throws SQLException {
        return CLOSE_CURSORS_AT_COMMIT;
    }

    // Default implementations for unsupported navigation/update methods
    @Override
    public void beforeFirst() throws SQLException { cursor = -1; }

    @Override
    public void afterLast() throws SQLException { cursor = result.getRowCount(); }

    @Override
    public boolean first() throws SQLException {
        if (result.getRowCount() == 0) return false;
        cursor = 0;
        return true;
    }

    @Override
    public boolean last() throws SQLException {
        if (result.getRowCount() == 0) return false;
        cursor = result.getRowCount() - 1;
        return true;
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        if (row < 1 || row > result.getRowCount()) return false;
        cursor = row - 1;
        return true;
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        int target = cursor + rows;
        if (target < 0 || target >= result.getRowCount()) return false;
        cursor = target;
        return true;
    }

    @Override
    public boolean previous() throws SQLException {
        if (cursor > 0) {
            cursor--;
            return true;
        }
        return false;
    }

    @Override public int getFetchDirection() throws SQLException { return FETCH_FORWARD; }
    @Override public void setFetchDirection(int direction) throws SQLException {}
    @Override public int getFetchSize() throws SQLException { return 0; }
    @Override public void setFetchSize(int rows) throws SQLException {}
    @Override public String getCursorName() throws SQLException { return null; }
    @Override public InputStream getAsciiStream(int columnIndex) throws SQLException { throw new SQLException("Streams not supported"); }
    @Override public InputStream getUnicodeStream(int columnIndex) throws SQLException { throw new SQLException("Streams not supported"); }
    @Override public InputStream getBinaryStream(int columnIndex) throws SQLException { throw new SQLException("Streams not supported"); }
    @Override public InputStream getAsciiStream(String columnLabel) throws SQLException { throw new SQLException("Streams not supported"); }
    @Override public InputStream getUnicodeStream(String columnLabel) throws SQLException { throw new SQLException("Streams not supported"); }
    @Override public InputStream getBinaryStream(String columnLabel) throws SQLException { throw new SQLException("Streams not supported"); }
    @Override public Reader getCharacterStream(int columnIndex) throws SQLException { throw new SQLException("Streams not supported"); }
    @Override public Reader getCharacterStream(String columnLabel) throws SQLException { throw new SQLException("Streams not supported"); }
    @Override public Ref getRef(int columnIndex) throws SQLException { throw new SQLException("Ref not supported"); }
    @Override public Blob getBlob(int columnIndex) throws SQLException { throw new SQLException("Blob not supported"); }
    @Override public Clob getClob(int columnIndex) throws SQLException { throw new SQLException("Clob not supported"); }
    @Override public Array getArray(int columnIndex) throws SQLException { throw new SQLException("Array not supported"); }
    @Override public Ref getRef(String columnLabel) throws SQLException { throw new SQLException("Ref not supported"); }
    @Override public Blob getBlob(String columnLabel) throws SQLException { throw new SQLException("Blob not supported"); }
    @Override public Clob getClob(String columnLabel) throws SQLException { throw new SQLException("Clob not supported"); }
    @Override public Array getArray(String columnLabel) throws SQLException { throw new SQLException("Array not supported"); }
    @Override public URL getURL(int columnIndex) throws SQLException { throw new SQLException("URL not supported"); }
    @Override public URL getURL(String columnLabel) throws SQLException { throw new SQLException("URL not supported"); }
    @Override public RowId getRowId(int columnIndex) throws SQLException { throw new SQLException("RowId not supported"); }
    @Override public RowId getRowId(String columnLabel) throws SQLException { throw new SQLException("RowId not supported"); }
    @Override public NClob getNClob(int columnIndex) throws SQLException { throw new SQLException("NClob not supported"); }
    @Override public NClob getNClob(String columnLabel) throws SQLException { throw new SQLException("NClob not supported"); }
    @Override public SQLXML getSQLXML(int columnIndex) throws SQLException { throw new SQLException("SQLXML not supported"); }
    @Override public SQLXML getSQLXML(String columnLabel) throws SQLException { throw new SQLException("SQLXML not supported"); }
    @Override public String getNString(int columnIndex) throws SQLException { return getString(columnIndex); }
    @Override public String getNString(String columnLabel) throws SQLException { return getString(columnLabel); }
    @Override public Reader getNCharacterStream(int columnIndex) throws SQLException { throw new SQLException("Streams not supported"); }
    @Override public Reader getNCharacterStream(String columnLabel) throws SQLException { throw new SQLException("Streams not supported"); }

    @Override public boolean rowUpdated() throws SQLException { return false; }
    @Override public boolean rowInserted() throws SQLException { return false; }
    @Override public boolean rowDeleted() throws SQLException { return false; }
    @Override public void updateNull(int columnIndex) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBoolean(int columnIndex, boolean x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateByte(int columnIndex, byte x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateShort(int columnIndex, short x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateInt(int columnIndex, int x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateLong(int columnIndex, long x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateFloat(int columnIndex, float x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateDouble(int columnIndex, double x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateString(int columnIndex, String x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBytes(int columnIndex, byte[] x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateDate(int columnIndex, Date x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateTime(int columnIndex, Time x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateObject(int columnIndex, Object x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateNull(String columnLabel) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBoolean(String columnLabel, boolean x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateByte(String columnLabel, byte x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateShort(String columnLabel, short x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateInt(String columnLabel, int x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateLong(String columnLabel, long x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateFloat(String columnLabel, float x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateDouble(String columnLabel, double x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateString(String columnLabel, String x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBytes(String columnLabel, byte[] x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateDate(String columnLabel, Date x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateTime(String columnLabel, Time x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateObject(String columnLabel, Object x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void insertRow() throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateRow() throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void deleteRow() throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void refreshRow() throws SQLException {}
    @Override public void cancelRowUpdates() throws SQLException {}
    @Override public void moveToInsertRow() throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void moveToCurrentRow() throws SQLException {}
    @Override public void updateRef(int columnIndex, Ref x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateRef(String columnLabel, Ref x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBlob(int columnIndex, Blob x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBlob(String columnLabel, Blob x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateClob(int columnIndex, Clob x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateClob(String columnLabel, Clob x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateArray(int columnIndex, Array x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateArray(String columnLabel, Array x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateRowId(int columnIndex, RowId x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateRowId(String columnLabel, RowId x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateNString(int columnIndex, String nString) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateNString(String columnLabel, String nString) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateNClob(int columnIndex, NClob nClob) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateNClob(String columnLabel, NClob nClob) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateClob(int columnIndex, Reader reader, long length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateClob(String columnLabel, Reader reader, long length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateCharacterStream(int columnIndex, Reader x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateClob(int columnIndex, Reader reader) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateClob(String columnLabel, Reader reader) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateNClob(int columnIndex, Reader reader) throws SQLException { throw new SQLException("Read-only ResultSet"); }
    @Override public void updateNClob(String columnLabel, Reader reader) throws SQLException { throw new SQLException("Read-only ResultSet"); }

    @Override public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        Object val = getObject(columnIndex);
        return type.cast(val);
    }
    @Override public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
    }
    @Override public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        return getObject(columnIndex);
    }
    @Override public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return getObject(columnLabel);
    }

    @Override public Date getDate(int columnIndex, Calendar cal) throws SQLException { return getDate(columnIndex); }
    @Override public Date getDate(String columnLabel, Calendar cal) throws SQLException { return getDate(columnLabel); }
    @Override public Time getTime(int columnIndex, Calendar cal) throws SQLException { return getTime(columnIndex); }
    @Override public Time getTime(String columnLabel, Calendar cal) throws SQLException { return getTime(columnLabel); }
    @Override public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException { return getTimestamp(columnIndex); }
    @Override public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException { return getTimestamp(columnLabel); }

    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Cannot unwrap to " + iface.getName());
    }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return iface.isInstance(this); }
}
