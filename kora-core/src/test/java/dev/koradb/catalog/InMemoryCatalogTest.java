package dev.koradb.catalog;

import dev.koradb.exception.SchemaException;
import dev.koradb.sql.ast.ColumnDef;
import dev.koradb.sql.ast.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link InMemoryCatalog}.
 */
@DisplayName("InMemoryCatalog")
class InMemoryCatalogTest {

    private InMemoryCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new InMemoryCatalog();
    }

    private Schema simpleSchema() {
        return new Schema(List.of(new ColumnDef("id", DataType.INT)));
    }

    private Table simpleTable(String name) {
        return new Table(name, simpleSchema());
    }

    // =========================================================================
    // Database management
    // =========================================================================

    @Nested
    @DisplayName("Database management")
    class DatabaseManagement {

        @Test
        @DisplayName("creates a database successfully")
        void create() {
            catalog.createDatabase("mydb");
            assertThat(catalog.listDatabases()).contains("mydb");
        }

        @Test
        @DisplayName("duplicate CREATE DATABASE throws SchemaException")
        void duplicateCreate() {
            catalog.createDatabase("mydb");
            assertThatThrownBy(() -> catalog.createDatabase("mydb"))
                    .isInstanceOf(SchemaException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        @DisplayName("CREATE DATABASE is case-insensitive")
        void caseInsensitiveCreate() {
            catalog.createDatabase("MyDB");
            assertThatThrownBy(() -> catalog.createDatabase("mydb"))
                    .isInstanceOf(SchemaException.class);
        }

        @Test
        @DisplayName("drops an existing database")
        void drop() {
            catalog.createDatabase("mydb");
            catalog.dropDatabase("mydb");
            assertThat(catalog.listDatabases()).doesNotContain("mydb");
        }

        @Test
        @DisplayName("DROP non-existent database throws SchemaException")
        void dropNonExistent() {
            assertThatThrownBy(() -> catalog.dropDatabase("ghost"))
                    .isInstanceOf(SchemaException.class)
                    .hasMessageContaining("ghost");
        }

        @Test
        @DisplayName("dropping the current database clears the current db")
        void dropCurrentDb() {
            catalog.createDatabase("mydb");
            catalog.useDatabase("mydb");
            catalog.dropDatabase("mydb");
            assertThat(catalog.currentDatabase()).isEmpty();
        }
    }

    // =========================================================================
    // Session state
    // =========================================================================

    @Nested
    @DisplayName("Session state (USE)")
    class SessionState {

        @Test
        @DisplayName("USE sets the current database")
        void useDatabase() {
            catalog.createDatabase("mydb");
            catalog.useDatabase("mydb");
            assertThat(catalog.currentDatabase()).isPresent();
            assertThat(catalog.currentDatabase().get()).isEqualTo("mydb");
        }

        @Test
        @DisplayName("USE on non-existent database throws SchemaException")
        void useNonExistent() {
            assertThatThrownBy(() -> catalog.useDatabase("ghost"))
                    .isInstanceOf(SchemaException.class);
        }

        @Test
        @DisplayName("No database selected initially")
        void noCurrentDb() {
            assertThat(catalog.currentDatabase()).isEmpty();
        }

        @Test
        @DisplayName("requireCurrentDatabase with no DB selected throws SchemaException")
        void requireCurrentDbFails() {
            assertThatThrownBy(() -> catalog.requireCurrentDatabase())
                    .isInstanceOf(SchemaException.class)
                    .hasMessageContaining("No database selected");
        }
    }

    // =========================================================================
    // Table management
    // =========================================================================

    @Nested
    @DisplayName("Table management")
    class TableManagement {

        @BeforeEach
        void selectDb() {
            catalog.createDatabase("mydb");
            catalog.useDatabase("mydb");
        }

        @Test
        @DisplayName("creates a table successfully")
        void create() {
            catalog.createTable(simpleTable("users"));
            assertThat(catalog.listTables()).hasSize(1);
        }

        @Test
        @DisplayName("getTable returns the created table")
        void getTable() {
            catalog.createTable(simpleTable("users"));
            Table t = catalog.getTable("users");
            assertThat(t.getName()).isEqualTo("users");
        }

        @Test
        @DisplayName("duplicate table name throws SchemaException")
        void duplicate() {
            catalog.createTable(simpleTable("users"));
            assertThatThrownBy(() -> catalog.createTable(simpleTable("users")))
                    .isInstanceOf(SchemaException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        @DisplayName("drops a table")
        void drop() {
            catalog.createTable(simpleTable("users"));
            catalog.dropTable("users");
            assertThat(catalog.listTables()).isEmpty();
        }

        @Test
        @DisplayName("DROP non-existent table throws SchemaException")
        void dropNonExistent() {
            assertThatThrownBy(() -> catalog.dropTable("ghost"))
                    .isInstanceOf(SchemaException.class);
        }

        @Test
        @DisplayName("GET non-existent table throws SchemaException")
        void getNonExistent() {
            assertThatThrownBy(() -> catalog.getTable("ghost"))
                    .isInstanceOf(SchemaException.class);
        }

        @Test
        @DisplayName("createTable without selecting a database throws SchemaException")
        void noDbSelected() {
            InMemoryCatalog fresh = new InMemoryCatalog();
            assertThatThrownBy(() -> fresh.createTable(simpleTable("t")))
                    .isInstanceOf(SchemaException.class)
                    .hasMessageContaining("No database selected");
        }

        @Test
        @DisplayName("table names are case-insensitive")
        void caseInsensitiveTableName() {
            catalog.createTable(simpleTable("Users"));
            Table t = catalog.getTable("users"); // different case
            assertThat(t.getName()).isEqualTo("Users");
        }
    }
}
