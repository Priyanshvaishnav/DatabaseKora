package dev.koradb.e2e;

import dev.koradb.KoraDB;
import dev.koradb.replication.model.NodeRole;
import dev.koradb.server.ServerInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static dev.koradb.e2e.E2e.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** VARCHAR(n): accepted in DDL, enforced on INSERT and UPDATE, and kept through restarts and over JDBC. */
class VarcharLengthRegressionTest {

    private Path dir;
    private KoraDB db;

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("kora-e2e-varchar");
        db = new KoraDB(dir);
        db.execute("CREATE DATABASE d");
        db.execute("USE d");
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
        E2e.deleteTree(dir);
    }

    @Test
    @DisplayName("INSERT accepts values up to n characters and rejects longer ones")
    void insertEnforcesLength() {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(5) NOT NULL, note VARCHAR(3))");
        db.execute("INSERT INTO t VALUES (1, 'abcde', NULL)");
        assertThatThrownBy(() -> db.execute("INSERT INTO t VALUES (2, 'abcdef', NULL)"))
                .hasMessageContaining("too long").hasMessageContaining("VARCHAR(5)");
        assertThat(count(db, "SELECT COUNT(*) FROM t")).isEqualTo(1);
    }

    @Test
    @DisplayName("A bad row in a multi-row INSERT inserts nothing")
    void multiRowInsertIsAllOrNothing() {
        db.execute("CREATE TABLE t (name VARCHAR(2))");
        assertThatThrownBy(() -> db.execute("INSERT INTO t VALUES ('ok'), ('too long')")).hasMessageContaining("too long");
        assertThat(count(db, "SELECT COUNT(*) FROM t")).isZero();
    }

    @Test
    @DisplayName("UPDATE enforces the length and leaves the row unchanged")
    void updateEnforcesLength() {
        db.execute("CREATE TABLE t (id INT, name VARCHAR(3))");
        db.execute("INSERT INTO t VALUES (1, 'abc')");
        assertThatThrownBy(() -> db.execute("UPDATE t SET name = 'abcd' WHERE id = 1")).hasMessageContaining("too long");
        assertThat(db.execute("SELECT name FROM t").rows().get(0).get("name")).isEqualTo("abc");
    }

    @Test
    @DisplayName("Length counts characters, not bytes; a number stored as text is checked too")
    void lengthCountsCharacters() {
        db.execute("CREATE TABLE t (name VARCHAR(5))");
        db.execute("INSERT INTO t VALUES ('Zürch'), ('日本語')");     // 5 characters, 6 bytes
        assertThatThrownBy(() -> db.execute("INSERT INTO t VALUES (123456)")).hasMessageContaining("too long");
        assertThat(count(db, "SELECT COUNT(*) FROM t")).isEqualTo(2);
    }

    @Test
    @DisplayName("Plain VARCHAR stays unlimited, and a bad length is a parse error")
    void plainVarcharAndBadLengths() {
        db.execute("CREATE TABLE t (name VARCHAR)");
        db.execute("INSERT INTO t VALUES ('" + "x".repeat(5_000) + "')");
        assertThat(count(db, "SELECT COUNT(*) FROM t")).isEqualTo(1);
        for (String bad : new String[]{"VARCHAR(0)", "VARCHAR(-1)", "VARCHAR(abc)", "VARCHAR()", "VARCHAR(5"}) {
            assertThatThrownBy(() -> db.execute("CREATE TABLE b (c " + bad + ")")).as(bad).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> db.execute("CREATE TABLE b (c INT(5))")).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("The length survives a restart, shows in DESCRIBE, and works with ALTER TABLE")
    void lengthSurvivesRestart() {
        db.execute("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(4) NOT NULL UNIQUE, free VARCHAR)");
        db.execute("ALTER TABLE t ADD COLUMN city VARCHAR(2)");
        db.close();
        db = new KoraDB(dir);
        db.execute("USE d");

        var described = db.execute("DESCRIBE t").rows();
        assertThat(described.get(1).get("data_type")).isEqualTo("VARCHAR(4)");
        assertThat(described.get(2).get("data_type")).isEqualTo("VARCHAR");
        assertThat(described.get(3).get("data_type")).isEqualTo("VARCHAR(2)");

        db.execute("INSERT INTO t VALUES (1, 'abcd', 'anything at all', 'NY')");
        assertThatThrownBy(() -> db.execute("INSERT INTO t VALUES (2, 'abcde', 'x', 'NY')")).hasMessageContaining("too long");
        assertThatThrownBy(() -> db.execute("INSERT INTO t VALUES (3, 'b', 'x', 'NYC')")).hasMessageContaining("too long");
        assertThatThrownBy(() -> db.execute("INSERT INTO t VALUES (4, NULL, 'x', 'NY')")).hasMessageContaining("NOT NULL");
    }

    @Test
    @DisplayName("JDBC: VARCHAR(255) DDL, COLUMN_SIZE in metadata, and an error for a value that is too long")
    void overJdbc() throws Exception {
        Path serverDir = Files.createTempDirectory("kora-e2e-varchar-srv");
        ServerInstance n = Servers.start(NodeRole.STANDALONE, serverDir, false);
        try {
            n.getDb().execute("CREATE DATABASE jdbc_db");
            try (Connection c = Servers.jdbc(n, "jdbc_db"); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE users (id INT PRIMARY KEY, email VARCHAR(255), code VARCHAR(2))");
                try (ResultSet cols = c.getMetaData().getColumns(null, null, "users", null)) {
                    cols.next();
                    cols.next();
                    assertThat(cols.getString("TYPE_NAME")).isEqualTo("VARCHAR");
                    assertThat(cols.getInt("COLUMN_SIZE")).isEqualTo(255);
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO users VALUES (?, ?, ?)")) {
                    ps.setInt(1, 1);
                    ps.setString(2, "a@example.com");
                    ps.setString(3, "toolong");
                    assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class).hasMessageContaining("too long");
                }
            }
        } finally {
            n.stop();
            E2e.deleteTree(serverDir);
        }
    }
}
