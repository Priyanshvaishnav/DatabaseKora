package dev.koradb.jdbc;

import dev.koradb.server.KoraServer;
import dev.koradb.server.KoraServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;

class KoraJdbcIntegrationTest {

    private KoraServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = new KoraServer(KoraServerConfig.inMemory(0));
        server.start();
        port = server.getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Should connect via DriverManager and execute basic DDL, DML, and queries")
    void testBasicJdbcOperations() throws SQLException {
        String url = "jdbc:koradb://127.0.0.1:" + port + "/app";

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            assertThat(conn).isNotNull();
            assertThat(conn.isClosed()).isFalse();

            // 1. Create table & insert data
            stmt.execute("CREATE TABLE products (id INT, name VARCHAR, price DOUBLE, in_stock BOOLEAN);");
            int affected = stmt.executeUpdate("INSERT INTO products VALUES (1, 'Monitor', 199.99, true);");
            assertThat(affected).isEqualTo(1);

            // 2. Query data
            try (ResultSet rs = stmt.executeQuery("SELECT id, name, price, in_stock FROM products;")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("id")).isEqualTo(1);
                assertThat(rs.getString("name")).isEqualTo("Monitor");
                assertThat(rs.getDouble("price")).isEqualTo(199.99);
                assertThat(rs.getBoolean("in_stock")).isTrue();
                assertThat(rs.wasNull()).isFalse();

                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("Should bind parameters using PreparedStatement safely")
    void testPreparedStatement() throws SQLException {
        String url = "jdbc:koradb://127.0.0.1:" + port + "/shop";

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE customers (id INT, name VARCHAR, credit DOUBLE);");

            // Insert via PreparedStatement
            String insertSql = "INSERT INTO customers VALUES (?, ?, ?);";
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                pstmt.setInt(1, 101);
                pstmt.setString(2, "Alice O'Connor"); // tests single-quote escaping
                pstmt.setDouble(3, 500.50);
                int count = pstmt.executeUpdate();
                assertThat(count).isEqualTo(1);

                pstmt.setInt(1, 102);
                pstmt.setString(2, "Bob");
                pstmt.setDouble(3, 150.00);
                pstmt.executeUpdate();
            }

            // Query via PreparedStatement
            String querySql = "SELECT * FROM customers WHERE id = ?;";
            try (PreparedStatement pstmt = conn.prepareStatement(querySql)) {
                pstmt.setInt(1, 101);
                try (ResultSet rs = pstmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("id")).isEqualTo(101);
                    assertThat(rs.getString("name")).isEqualTo("Alice O'Connor");
                    assertThat(rs.getDouble("credit")).isEqualTo(500.50);
                    assertThat(rs.next()).isFalse();
                }
            }
        }
    }

    @Test
    @DisplayName("Should provide accurate ResultSetMetaData")
    void testResultSetMetaData() throws SQLException {
        String url = "jdbc:koradb://127.0.0.1:" + port + "/meta_test";

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE metrics (id INT, metric_name VARCHAR, val DOUBLE);");
            stmt.execute("INSERT INTO metrics VALUES (1, 'cpu', 42.5);");

            try (ResultSet rs = stmt.executeQuery("SELECT id, metric_name, val FROM metrics;")) {
                ResultSetMetaData meta = rs.getMetaData();
                assertThat(meta.getColumnCount()).isEqualTo(3);

                assertThat(meta.getColumnName(1)).isEqualToIgnoringCase("id");
                assertThat(meta.getColumnType(1)).isEqualTo(Types.INTEGER);

                assertThat(meta.getColumnName(2)).isEqualToIgnoringCase("metric_name");
                assertThat(meta.getColumnType(2)).isEqualTo(Types.VARCHAR);

                assertThat(meta.getColumnName(3)).isEqualToIgnoringCase("val");
                assertThat(meta.getColumnType(3)).isEqualTo(Types.DOUBLE);
            }
        }
    }

    @Test
    @DisplayName("Should manage manual transactions with commit and rollback")
    void testTransactionControl() throws SQLException {
        String url = "jdbc:koradb://127.0.0.1:" + port + "/tx_test";

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE ledger (id INT, amount DOUBLE);");

            // Disable autoCommit
            conn.setAutoCommit(false);
            assertThat(conn.getAutoCommit()).isFalse();

            // Insert and rollback
            stmt.executeUpdate("INSERT INTO ledger VALUES (1, 100.0);");
            conn.rollback();

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ledger;")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }

            // Insert and commit
            stmt.executeUpdate("INSERT INTO ledger VALUES (2, 250.0);");
            conn.commit();

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ledger;")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("Should inspect schema through DatabaseMetaData")
    void testDatabaseMetaData() throws SQLException {
        String url = "jdbc:koradb://127.0.0.1:" + port + "/catalog_test";

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE orders (id INT, total DOUBLE);");

            DatabaseMetaData meta = conn.getMetaData();
            assertThat(meta.getDatabaseProductName()).isEqualTo("KoraDB");
            assertThat(meta.getDriverName()).isEqualTo("KoraDB JDBC Driver");

            // Inspect tables
            try (ResultSet rs = meta.getTables(null, null, "orders", null)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("TABLE_NAME")).isEqualToIgnoringCase("orders");
                assertThat(rs.getString("TABLE_TYPE")).isEqualTo("TABLE");
            }

            // Inspect columns
            try (ResultSet rs = meta.getColumns(null, null, "orders", null)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("COLUMN_NAME")).isEqualToIgnoringCase("id");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("COLUMN_NAME")).isEqualToIgnoringCase("total");
            }
        }
    }
}
