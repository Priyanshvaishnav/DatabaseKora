package dev.koradb.jdbc;

import dev.koradb.client.ClientQueryResult;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * JDBC ResultSetMetaData implementation for KoraDB.
 */
public class KoraResultSetMetaData implements ResultSetMetaData {

    private final ClientQueryResult result;

    public KoraResultSetMetaData(ClientQueryResult result) {
        this.result = result;
    }

    @Override
    public int getColumnCount() throws SQLException {
        return result.getColumnCount();
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        return false;
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        return false;
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        return true;
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        return false;
    }

    @Override
    public int isNullable(int column) throws SQLException {
        return columnNullable;
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        int type = getColumnType(column);
        return type == Types.INTEGER || type == Types.BIGINT || type == Types.DOUBLE;
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        return 32;
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        return getColumnName(column);
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        checkColumn(column);
        return result.columns().get(column - 1);
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        return "";
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        return 0;
    }

    @Override
    public int getScale(int column) throws SQLException {
        return 0;
    }

    @Override
    public String getTableName(int column) throws SQLException {
        return "";
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        return "";
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        checkColumn(column);
        int colIdx = column - 1;

        // Inspect row values to infer type
        for (var row : result.rows()) {
            if (colIdx < row.size()) {
                Object val = row.get(colIdx);
                if (val != null) {
                    if (val instanceof Integer) return Types.INTEGER;
                    if (val instanceof Long) return Types.BIGINT;
                    if (val instanceof Double || val instanceof Float) return Types.DOUBLE;
                    if (val instanceof Boolean) return Types.BOOLEAN;
                    if (val instanceof java.time.temporal.Temporal) return Types.TIMESTAMP;
                    return Types.VARCHAR;
                }
            }
        }

        return Types.VARCHAR;
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        return switch (getColumnType(column)) {
            case Types.INTEGER -> "INT";
            case Types.BIGINT  -> "BIGINT";
            case Types.DOUBLE  -> "DOUBLE";
            case Types.BOOLEAN -> "BOOLEAN";
            case Types.TIMESTAMP -> "TIMESTAMP";
            default            -> "VARCHAR";
        };
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        return true;
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        return false;
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        return false;
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        return switch (getColumnType(column)) {
            case Types.INTEGER -> Integer.class.getName();
            case Types.BIGINT  -> Long.class.getName();
            case Types.DOUBLE  -> Double.class.getName();
            case Types.BOOLEAN -> Boolean.class.getName();
            default            -> String.class.getName();
        };
    }

    private void checkColumn(int column) throws SQLException {
        if (column < 1 || column > result.getColumnCount()) {
            throw new SQLException("Invalid column index: " + column + " (column count: " + result.getColumnCount() + ")");
        }
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
