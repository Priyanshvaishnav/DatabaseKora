package dev.koradb.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KoraJdbcUrlTest {

    @Test
    @DisplayName("Should parse standard URL with host, port, and database")
    void testStandardUrl() throws SQLException {
        KoraJdbcUrl url = KoraJdbcUrl.parse("jdbc:koradb://localhost:8765/ecommerce", new Properties());

        assertThat(url.host()).isEqualTo("localhost");
        assertThat(url.port()).isEqualTo(8765);
        assertThat(url.database()).isEqualTo("ecommerce");
    }

    @Test
    @DisplayName("Should parse URL with default port and query parameters")
    void testUrlWithQueryParams() throws SQLException {
        KoraJdbcUrl url = KoraJdbcUrl.parse("jdbc:koradb://127.0.0.1:8765/finance?autoCommit=false&timeout=5000", null);

        assertThat(url.host()).isEqualTo("127.0.0.1");
        assertThat(url.port()).isEqualTo(8765);
        assertThat(url.database()).isEqualTo("finance");
        assertThat(url.getBooleanProperty("autoCommit", true)).isFalse();
        assertThat(url.getProperty("timeout", "")).isEqualTo("5000");
    }

    @Test
    @DisplayName("Should parse simplified URL with only database name")
    void testSimplifiedUrl() throws SQLException {
        KoraJdbcUrl url = KoraJdbcUrl.parse("jdbc:koradb:testdb", null);

        assertThat(url.host()).isEqualTo("127.0.0.1");
        assertThat(url.port()).isEqualTo(8765);
        assertThat(url.database()).isEqualTo("testdb");
    }

    @Test
    @DisplayName("Should accept valid KoraDB URLs and reject invalid prefixes")
    void testAccepts() {
        assertThat(KoraJdbcUrl.accepts("jdbc:koradb://localhost:8765/db")).isTrue();
        assertThat(KoraJdbcUrl.accepts("jdbc:koradb:shop")).isTrue();
        assertThat(KoraJdbcUrl.accepts("jdbc:mysql://localhost:3306/db")).isFalse();
        assertThat(KoraJdbcUrl.accepts("jdbc:postgresql://localhost:5432/db")).isFalse();
        assertThat(KoraJdbcUrl.accepts(null)).isFalse();
    }

    @Test
    @DisplayName("Should reject invalid URLs")
    void testInvalidUrls() {
        assertThatThrownBy(() -> KoraJdbcUrl.parse("jdbc:mysql://localhost:3306", null))
                .isInstanceOf(SQLException.class);
    }
}
