package dev.koradb.jdbc;

import dev.koradb.client.ClientQueryResult;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC DatabaseMetaData implementation for KoraDB.
 */
public class KoraDatabaseMetaData implements DatabaseMetaData {

    private final KoraConnection connection;
    private final KoraJdbcUrl jdbcUrl;

    public KoraDatabaseMetaData(KoraConnection connection, KoraJdbcUrl jdbcUrl) {
        this.connection = connection;
        this.jdbcUrl = jdbcUrl;
    }

    @Override
    public boolean allProceduresAreCallable() throws SQLException { return false; }
    @Override
    public boolean allTablesAreSelectable() throws SQLException { return true; }
    @Override
    public String getURL() throws SQLException { return "jdbc:koradb://" + jdbcUrl.host() + ":" + jdbcUrl.port() + "/" + connection.getCatalog(); }
    @Override
    public String getUserName() throws SQLException { return "koradb"; }
    @Override
    public boolean isReadOnly() throws SQLException { return connection.isReadOnly(); }
    @Override
    public boolean nullsAreSortedHigh() throws SQLException { return false; }
    @Override
    public boolean nullsAreSortedLow() throws SQLException { return true; }
    @Override
    public boolean nullsAreSortedAtStart() throws SQLException { return false; }
    @Override
    public boolean nullsAreSortedAtEnd() throws SQLException { return false; }
    @Override
    public String getDatabaseProductName() throws SQLException { return "KoraDB"; }
    @Override
    public String getDatabaseProductVersion() throws SQLException { return "2.0.1"; }
    @Override
    public String getDriverName() throws SQLException { return "KoraDB JDBC Driver"; }
    @Override
    public String getDriverVersion() throws SQLException { return "2.0.1"; }
    @Override
    public int getDriverMajorVersion() { return 2; }
    @Override
    public int getDriverMinorVersion() { return 0; }
    @Override
    public boolean usesLocalFiles() throws SQLException { return false; }
    @Override
    public boolean usesLocalFilePerTable() throws SQLException { return true; }
    @Override
    public boolean supportsMixedCaseIdentifiers() throws SQLException { return false; }
    @Override
    public boolean storesUpperCaseIdentifiers() throws SQLException { return false; }
    @Override
    public boolean storesLowerCaseIdentifiers() throws SQLException { return true; }
    @Override
    public boolean storesMixedCaseIdentifiers() throws SQLException { return false; }
    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException { return true; }
    @Override
    public boolean storesUpperCaseQuotedIdentifiers() throws SQLException { return false; }
    @Override
    public boolean storesLowerCaseQuotedIdentifiers() throws SQLException { return false; }
    @Override
    public boolean storesMixedCaseQuotedIdentifiers() throws SQLException { return true; }
    @Override
    public String getIdentifierQuoteString() throws SQLException { return "\""; }
    @Override
    public String getSQLKeywords() throws SQLException { return "EXPLAIN,ANALYZE"; }
    @Override
    public String getNumericFunctions() throws SQLException { return "COUNT,SUM,AVG,MIN,MAX"; }
    @Override
    public String getStringFunctions() throws SQLException { return ""; }
    @Override
    public String getSystemFunctions() throws SQLException { return ""; }
    @Override
    public String getTimeDateFunctions() throws SQLException { return ""; }
    @Override
    public String getSearchStringEscape() throws SQLException { return "\\"; }
    @Override
    public String getExtraNameCharacters() throws SQLException { return ""; }
    @Override
    public boolean supportsAlterTableWithAddColumn() throws SQLException { return false; }
    @Override
    public boolean supportsAlterTableWithDropColumn() throws SQLException { return false; }
    @Override
    public boolean supportsColumnAliasing() throws SQLException { return true; }
    @Override
    public boolean nullPlusNonNullIsNull() throws SQLException { return true; }
    @Override
    public boolean supportsConvert() throws SQLException { return false; }
    @Override
    public boolean supportsConvert(int fromType, int toType) throws SQLException { return false; }
    @Override
    public boolean supportsTableCorrelationNames() throws SQLException { return true; }
    @Override
    public boolean supportsDifferentTableCorrelationNames() throws SQLException { return false; }
    @Override
    public boolean supportsExpressionsInOrderBy() throws SQLException { return true; }
    @Override
    public boolean supportsOrderByUnrelated() throws SQLException { return true; }
    @Override
    public boolean supportsGroupBy() throws SQLException { return true; }
    @Override
    public boolean supportsGroupByUnrelated() throws SQLException { return true; }
    @Override
    public boolean supportsGroupByBeyondSelect() throws SQLException { return true; }
    @Override
    public boolean supportsLikeEscapeClause() throws SQLException { return true; }
    @Override
    public boolean supportsMultipleResultSets() throws SQLException { return false; }
    @Override
    public boolean supportsMultipleTransactions() throws SQLException { return true; }
    @Override
    public boolean supportsNonNullableColumns() throws SQLException { return true; }
    @Override
    public boolean supportsMinimumSQLGrammar() throws SQLException { return true; }
    @Override
    public boolean supportsCoreSQLGrammar() throws SQLException { return true; }
    @Override
    public boolean supportsExtendedSQLGrammar() throws SQLException { return false; }
    @Override
    public boolean supportsANSI92EntryLevelSQL() throws SQLException { return true; }
    @Override
    public boolean supportsANSI92IntermediateSQL() throws SQLException { return false; }
    @Override
    public boolean supportsANSI92FullSQL() throws SQLException { return false; }
    @Override
    public boolean supportsIntegrityEnhancementFacility() throws SQLException { return false; }
    @Override
    public boolean supportsOuterJoins() throws SQLException { return false; }
    @Override
    public boolean supportsFullOuterJoins() throws SQLException { return false; }
    @Override
    public boolean supportsLimitedOuterJoins() throws SQLException { return false; }
    @Override
    public String getSchemaTerm() throws SQLException { return "schema"; }
    @Override
    public String getProcedureTerm() throws SQLException { return "procedure"; }
    @Override
    public String getCatalogTerm() throws SQLException { return "database"; }
    @Override
    public boolean isCatalogAtStart() throws SQLException { return true; }
    @Override
    public String getCatalogSeparator() throws SQLException { return "."; }
    @Override
    public boolean supportsSchemasInDataManipulation() throws SQLException { return false; }
    @Override
    public boolean supportsSchemasInProcedureCalls() throws SQLException { return false; }
    @Override
    public boolean supportsSchemasInTableDefinitions() throws SQLException { return false; }
    @Override
    public boolean supportsSchemasInIndexDefinitions() throws SQLException { return false; }
    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException { return false; }
    @Override
    public boolean supportsCatalogsInDataManipulation() throws SQLException { return true; }
    @Override
    public boolean supportsCatalogsInProcedureCalls() throws SQLException { return false; }
    @Override
    public boolean supportsCatalogsInTableDefinitions() throws SQLException { return true; }
    @Override
    public boolean supportsCatalogsInIndexDefinitions() throws SQLException { return true; }
    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException { return false; }
    @Override
    public boolean supportsPositionedDelete() throws SQLException { return false; }
    @Override
    public boolean supportsPositionedUpdate() throws SQLException { return false; }
    @Override
    public boolean supportsSelectForUpdate() throws SQLException { return false; }
    @Override
    public boolean supportsStoredProcedures() throws SQLException { return false; }
    @Override
    public boolean supportsSubqueriesInComparisons() throws SQLException { return false; }
    @Override
    public boolean supportsSubqueriesInExists() throws SQLException { return false; }
    @Override
    public boolean supportsSubqueriesInIns() throws SQLException { return false; }
    @Override
    public boolean supportsSubqueriesInQuantifieds() throws SQLException { return false; }
    @Override
    public boolean supportsCorrelatedSubqueries() throws SQLException { return false; }
    @Override
    public boolean supportsUnion() throws SQLException { return false; }
    @Override
    public boolean supportsUnionAll() throws SQLException { return false; }
    @Override
    public boolean supportsOpenCursorsAcrossCommit() throws SQLException { return false; }
    @Override
    public boolean supportsOpenCursorsAcrossRollback() throws SQLException { return false; }
    @Override
    public boolean supportsOpenStatementsAcrossCommit() throws SQLException { return true; }
    @Override
    public boolean supportsOpenStatementsAcrossRollback() throws SQLException { return true; }
    @Override
    public int getMaxBinaryLiteralLength() throws SQLException { return 0; }
    @Override
    public int getMaxCharLiteralLength() throws SQLException { return 0; }
    @Override
    public int getMaxColumnNameLength() throws SQLException { return 64; }
    @Override
    public int getMaxColumnsInGroupBy() throws SQLException { return 16; }
    @Override
    public int getMaxColumnsInIndex() throws SQLException { return 16; }
    @Override
    public int getMaxColumnsInOrderBy() throws SQLException { return 16; }
    @Override
    public int getMaxColumnsInSelect() throws SQLException { return 128; }
    @Override
    public int getMaxColumnsInTable() throws SQLException { return 128; }
    @Override
    public int getMaxConnections() throws SQLException { return 1000; }
    @Override
    public int getMaxCursorNameLength() throws SQLException { return 0; }
    @Override
    public int getMaxIndexLength() throws SQLException { return 0; }
    @Override
    public int getMaxSchemaNameLength() throws SQLException { return 64; }
    @Override
    public int getMaxProcedureNameLength() throws SQLException { return 0; }
    @Override
    public int getMaxCatalogNameLength() throws SQLException { return 64; }
    @Override
    public int getMaxRowSize() throws SQLException { return 4096; }
    @Override
    public boolean doesMaxRowSizeIncludeBlobs() throws SQLException { return false; }
    @Override
    public int getMaxStatementLength() throws SQLException { return 65536; }
    @Override
    public int getMaxStatements() throws SQLException { return 0; }
    @Override
    public int getMaxTableNameLength() throws SQLException { return 64; }
    @Override
    public int getMaxTablesInSelect() throws SQLException { return 16; }
    @Override
    public int getMaxUserNameLength() throws SQLException { return 64; }
    @Override
    public int getDefaultTransactionIsolation() throws SQLException { return Connection.TRANSACTION_SERIALIZABLE; }
    @Override
    public boolean supportsTransactions() throws SQLException { return true; }
    @Override
    public boolean supportsTransactionIsolationLevel(int level) throws SQLException {
        return level == Connection.TRANSACTION_READ_COMMITTED ||
               level == Connection.TRANSACTION_REPEATABLE_READ ||
               level == Connection.TRANSACTION_SERIALIZABLE;
    }
    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException { return true; }
    @Override
    public boolean supportsDataManipulationTransactionsOnly() throws SQLException { return false; }
    @Override
    public boolean dataDefinitionCausesTransactionCommit() throws SQLException { return false; }
    @Override
    public boolean dataDefinitionIgnoredInTransactions() throws SQLException { return false; }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
        List<String> cols = List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS");
        List<List<Object>> rows = new ArrayList<>();

        try {
            ClientQueryResult tablesResult = connection.executeRaw("SHOW TABLES;");
            String cat = connection.getCatalog();

            for (var row : tablesResult.rows()) {
                if (!row.isEmpty()) {
                    String tblName = row.get(0).toString();
                    if (tableNamePattern == null || tableNamePattern.equals("%") || tblName.equalsIgnoreCase(tableNamePattern)) {
                        rows.add(List.of(cat, "", tblName, "TABLE", ""));
                    }
                }
            }
        } catch (Exception e) {
            // Empty result if error
        }

        ClientQueryResult res = new ClientQueryResult(true, 0, "OK", cols, rows, 0);
        return new KoraResultSet(null, res);
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        List<String> cols = List.of(
                "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME",
                "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH",
                "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS",
                "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB",
                "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE"
        );
        List<List<Object>> rows = new ArrayList<>();

        if (tableNamePattern != null && !tableNamePattern.equals("%")) {
            try {
                ClientQueryResult desc = connection.executeRaw("DESCRIBE " + tableNamePattern + ";");
                int pos = 1;
                for (var r : desc.rows()) {
                    if (r.size() >= 2) {
                        String colName = r.get(0).toString();
                        // INT, VARCHAR or VARCHAR(50)
                        String[] type = r.get(1).toString().toUpperCase().split("[()]");
                        String typeStr = type[0];
                        int columnSize = type.length == 2 ? Integer.parseInt(type[1]) : 32;

                        int dataType = switch (typeStr) {
                            case "INT"       -> Types.INTEGER;
                            case "BIGINT"    -> Types.BIGINT;
                            case "DOUBLE"    -> Types.DOUBLE;
                            case "BOOLEAN"   -> Types.BOOLEAN;
                            case "TIMESTAMP" -> Types.TIMESTAMP;
                            default          -> Types.VARCHAR;
                        };

                        rows.add(List.of(
                                connection.getCatalog(), "", tableNamePattern, colName,
                                dataType, typeStr, columnSize, 0,
                                0, 10, DatabaseMetaData.columnNullable, "",
                                "", 0, 0,
                                32, pos++, "YES"
                        ));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        ClientQueryResult res = new ClientQueryResult(true, 0, "OK", cols, rows, 0);
        return new KoraResultSet(null, res);
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        List<String> cols = List.of("TABLE_CAT");
        List<List<Object>> rows = new ArrayList<>();
        try {
            ClientQueryResult res = connection.executeRaw("SHOW DATABASES;");
            for (var r : res.rows()) {
                if (!r.isEmpty()) {
                    rows.add(List.of(r.get(0).toString()));
                }
            }
        } catch (Exception ignored) {
        }
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", cols, rows, 0));
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        List<String> cols = List.of("TABLE_TYPE");
        List<List<Object>> rows = List.of(List.of("TABLE"));
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", cols, rows, 0));
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        List<String> cols = List.of("TABLE_SCHEM", "TABLE_CATALOG");
        List<List<Object>> rows = List.of(List.of("", connection.getCatalog()));
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", cols, rows, 0));
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connection;
    }

    @Override public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("PROCEDURE_NAME"), List.of(), 0));
    }
    @Override public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("COLUMN_NAME"), List.of(), 0));
    }
    @Override public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("PRIVILEGE"), List.of(), 0));
    }
    @Override public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("PRIVILEGE"), List.of(), 0));
    }
    @Override public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("COLUMN_NAME"), List.of(), 0));
    }
    @Override public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("COLUMN_NAME"), List.of(), 0));
    }
    @Override public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"), List.of(), 0));
    }
    @Override public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("PKTABLE_NAME", "FKTABLE_NAME"), List.of(), 0));
    }
    @Override public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("PKTABLE_NAME", "FKTABLE_NAME"), List.of(), 0));
    }
    @Override public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable, String foreignCatalog, String foreignSchema, String foreignTable) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("PKTABLE_NAME", "FKTABLE_NAME"), List.of(), 0));
    }
    @Override public ResultSet getTypeInfo() throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("TYPE_NAME", "DATA_TYPE"), List.of(), 0));
    }
    @Override public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("TABLE_NAME", "NON_UNIQUE", "INDEX_NAME"), List.of(), 0));
    }

    @Override public boolean supportsResultSetType(int type) throws SQLException { return type == ResultSet.TYPE_FORWARD_ONLY; }
    @Override public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException { return concurrency == ResultSet.CONCUR_READ_ONLY; }
    @Override public boolean ownUpdatesAreVisible(int type) throws SQLException { return false; }
    @Override public boolean ownDeletesAreVisible(int type) throws SQLException { return false; }
    @Override public boolean ownInsertsAreVisible(int type) throws SQLException { return false; }
    @Override public boolean othersUpdatesAreVisible(int type) throws SQLException { return false; }
    @Override public boolean othersDeletesAreVisible(int type) throws SQLException { return false; }
    @Override public boolean othersInsertsAreVisible(int type) throws SQLException { return false; }
    @Override public boolean updatesAreDetected(int type) throws SQLException { return false; }
    @Override public boolean deletesAreDetected(int type) throws SQLException { return false; }
    @Override public boolean insertsAreDetected(int type) throws SQLException { return false; }
    @Override public boolean supportsBatchUpdates() throws SQLException { return false; }
    @Override public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("TYPE_NAME"), List.of(), 0));
    }
    @Override public boolean supportsSavepoints() throws SQLException { return false; }
    @Override public boolean supportsNamedParameters() throws SQLException { return false; }
    @Override public boolean supportsMultipleOpenResults() throws SQLException { return false; }
    @Override public boolean supportsGetGeneratedKeys() throws SQLException { return false; }
    @Override public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("TYPE_NAME"), List.of(), 0));
    }
    @Override public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("TABLE_NAME"), List.of(), 0));
    }
    @Override public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("ATTR_NAME"), List.of(), 0));
    }
    @Override public boolean supportsResultSetHoldability(int holdability) throws SQLException { return true; }
    @Override public int getResultSetHoldability() throws SQLException { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public int getDatabaseMajorVersion() throws SQLException { return 2; }
    @Override public int getDatabaseMinorVersion() throws SQLException { return 0; }
    @Override public int getJDBCMajorVersion() throws SQLException { return 4; }
    @Override public int getJDBCMinorVersion() throws SQLException { return 2; }
    @Override public int getSQLStateType() throws SQLException { return sqlStateSQL99; }
    @Override public boolean locatorsUpdateCopy() throws SQLException { return false; }
    @Override public boolean supportsStatementPooling() throws SQLException { return false; }
    @Override public RowIdLifetime getRowIdLifetime() throws SQLException { return RowIdLifetime.ROWID_UNSUPPORTED; }
    @Override public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException { return getSchemas(); }
    @Override public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException { return false; }
    @Override public boolean autoCommitFailureClosesAllResultSets() throws SQLException { return false; }
    @Override public ResultSet getClientInfoProperties() throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION"), List.of(), 0));
    }
    @Override public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("FUNCTION_NAME"), List.of(), 0));
    }
    @Override public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("COLUMN_NAME"), List.of(), 0));
    }
    @Override public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        return new KoraResultSet(null, new ClientQueryResult(true, 0, "OK", List.of("COLUMN_NAME"), List.of(), 0));
    }
    @Override public boolean generatedKeyAlwaysReturned() throws SQLException { return false; }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Cannot unwrap to " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }
}
