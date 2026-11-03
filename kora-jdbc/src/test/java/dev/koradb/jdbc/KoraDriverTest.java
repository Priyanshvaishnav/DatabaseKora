package dev.koradb.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class KoraDriverTest {

    @Test
    @DisplayName("Should be registered with DriverManager")
    void testRegisteredWithDriverManager() throws SQLException {
        Driver driver = DriverManager.getDriver("jdbc:koradb://localhost:8765/demo");
        assertThat(driver).isNotNull();
        assertThat(driver).isInstanceOf(KoraDriver.class);
    }

    @Test
    @DisplayName("Should return null for non-matching URLs")
    void testConnectNonMatchingUrl() throws SQLException {
        KoraDriver driver = new KoraDriver();
        assertThat(driver.connect("jdbc:postgresql://localhost:5432/mydb", new Properties())).isNull();
    }

    @Test
    @DisplayName("Should report correct driver metadata")
    void testDriverMetadata() {
        KoraDriver driver = new KoraDriver();
        assertThat(driver.getMajorVersion()).isEqualTo(2);
        assertThat(driver.getMinorVersion()).isEqualTo(0);
        assertThat(driver.jdbcCompliant()).isFalse();
    }
}
