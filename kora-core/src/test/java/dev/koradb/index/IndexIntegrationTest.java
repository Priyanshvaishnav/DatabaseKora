package dev.koradb.index;

import dev.koradb.KoraDB;
import dev.koradb.exception.StorageException;
import dev.koradb.execution.QueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Index Integration — End-to-End & Disk Persistence")
class IndexIntegrationTest {

    @TempDir
    Path dataDir;

    @Test
    @DisplayName("CREATE INDEX on populated table enables point lookups")
    void createIndexOnPopulatedTableAndLookup() {
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE testdb");
            db.execute("USE testdb");
            db.execute("CREATE TABLE users (id INT, name VARCHAR, age INT)");
            db.execute("INSERT INTO users VALUES (1, 'Alice', 30)");
            db.execute("INSERT INTO users VALUES (2, 'Bob', 25)");
            db.execute("INSERT INTO users VALUES (3, 'Charlie', 35)");

            db.execute("CREATE INDEX idx_users_id ON users (id)");

            QueryResult res = db.execute("SELECT name FROM users WHERE id = 2");
            assertThat(res.rows()).hasSize(1);
            assertThat(res.rows().get(0).get("name")).isEqualTo("Bob");

            QueryResult notFound = db.execute("SELECT name FROM users WHERE id = 99");
            assertThat(notFound.rows()).isEmpty();
        }
    }

    @Test
    @DisplayName("INSERT updates existing index automatically")
    void insertUpdatesIndexAutomatically() {
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE store");
            db.execute("USE store");
            db.execute("CREATE TABLE items (sku VARCHAR, qty INT)");
            db.execute("CREATE INDEX idx_sku ON items (sku)");

            db.execute("INSERT INTO items VALUES ('SKU-100', 10)");
            db.execute("INSERT INTO items VALUES ('SKU-200', 50)");

            QueryResult r1 = db.execute("SELECT qty FROM items WHERE sku = 'SKU-100'");
            assertThat(r1.rows()).hasSize(1);
            assertThat(r1.rows().get(0).get("qty")).isEqualTo(10);

            QueryResult r2 = db.execute("SELECT qty FROM items WHERE sku = 'SKU-200'");
            assertThat(r2.rows()).hasSize(1);
            assertThat(r2.rows().get(0).get("qty")).isEqualTo(50);
        }
    }

    @Test
    @DisplayName("UNIQUE INDEX prevents inserting duplicate keys")
    void uniqueIndexRejectsDuplicates() {
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE accounts_db");
            db.execute("USE accounts_db");
            db.execute("CREATE TABLE accounts (email VARCHAR, balance DOUBLE)");
            db.execute("CREATE UNIQUE INDEX idx_email ON accounts (email)");

            db.execute("INSERT INTO accounts VALUES ('alice@example.com', 100.0)");

            assertThatThrownBy(() -> db.execute("INSERT INTO accounts VALUES ('alice@example.com', 200.0)"))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("Unique index violation");
        }
    }

    @Test
    @DisplayName("DELETE removes row and index lookup returns empty")
    void deleteAndLookupWithIndex() {
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE library");
            db.execute("USE library");
            db.execute("CREATE TABLE books (id INT, title VARCHAR)");
            db.execute("CREATE INDEX idx_books_id ON books (id)");

            db.execute("INSERT INTO books VALUES (1, 'Book A')");
            db.execute("INSERT INTO books VALUES (2, 'Book B')");
            db.execute("INSERT INTO books VALUES (3, 'Book C')");

            db.execute("DELETE FROM books WHERE id = 2");

            QueryResult r2 = db.execute("SELECT title FROM books WHERE id = 2");
            assertThat(r2.rows()).isEmpty();

            QueryResult r1 = db.execute("SELECT title FROM books WHERE id = 1");
            assertThat(r1.rows()).hasSize(1);
            assertThat(r1.rows().get(0).get("title")).isEqualTo("Book A");

            QueryResult r3 = db.execute("SELECT title FROM books WHERE id = 3");
            assertThat(r3.rows()).hasSize(1);
            assertThat(r3.rows().get(0).get("title")).isEqualTo("Book C");
        }
    }

    @Test
    @DisplayName("Indexes survive simulated JVM restart")
    void indexSurvivesRestart() {
        // Session 1: populate and create index
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE enterprise");
            db.execute("USE enterprise");
            db.execute("CREATE TABLE clients (id INT, company VARCHAR)");
            db.execute("CREATE INDEX idx_client_id ON clients (id)");

            for (int i = 1; i <= 50; i++) {
                db.execute("INSERT INTO clients VALUES (" + i + ", 'Company " + i + "')");
            }
        }

        // Session 2: reopen and query using index
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("USE enterprise");

            QueryResult r25 = db.execute("SELECT company FROM clients WHERE id = 25");
            assertThat(r25.rows()).hasSize(1);
            assertThat(r25.rows().get(0).get("company")).isEqualTo("Company 25");

            // Insert new client in session 2
            db.execute("INSERT INTO clients VALUES (51, 'Company 51')");

            QueryResult r51 = db.execute("SELECT company FROM clients WHERE id = 51");
            assertThat(r51.rows()).hasSize(1);
            assertThat(r51.rows().get(0).get("company")).isEqualTo("Company 51");
        }
    }

    @Test
    @DisplayName("DROP INDEX removes index metadata and queries still succeed")
    void dropIndex() {
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE drop_db");
            db.execute("USE drop_db");
            db.execute("CREATE TABLE products (id INT, name VARCHAR)");
            db.execute("INSERT INTO products VALUES (1, 'Widget')");
            db.execute("CREATE INDEX idx_p_id ON products (id)");

            db.execute("DROP INDEX idx_p_id ON products");

            // Query still succeeds via full table scan
            QueryResult r = db.execute("SELECT name FROM products WHERE id = 1");
            assertThat(r.rows()).hasSize(1);
            assertThat(r.rows().get(0).get("name")).isEqualTo("Widget");
        }
    }
}
