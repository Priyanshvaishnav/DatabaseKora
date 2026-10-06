package dev.koradb.ai.schema;

import dev.koradb.catalog.Catalog;
import dev.koradb.catalog.InMemoryCatalog;
import dev.koradb.catalog.Schema;
import dev.koradb.catalog.Table;
import dev.koradb.sql.ast.ColumnDef;
import dev.koradb.sql.ast.DataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaExtractorTest {

    @Test
    @DisplayName("Extract database schema from catalog with tables and indexes")
    void testExtractFromCatalog() {
        Catalog catalog = new InMemoryCatalog();
        catalog.createDatabase("shop");
        catalog.useDatabase("shop");

        Table usersTable = new Table("users", new Schema(List.of(
                new ColumnDef("id", DataType.INT),
                new ColumnDef("name", DataType.VARCHAR),
                new ColumnDef("email", DataType.VARCHAR),
                new ColumnDef("balance", DataType.DOUBLE)
        )));
        catalog.createTable(usersTable);
        catalog.createIndex("idx_users_email", "users", "email", true);

        DatabaseSchema schema = SchemaExtractor.fromCatalog(catalog, "shop");

        assertThat(schema.databaseName()).isEqualTo("shop");
        assertThat(schema.hasTable("users")).isTrue();

        TableSchema ts = schema.getTable("users");
        assertThat(ts).isNotNull();
        assertThat(ts.columns()).hasSize(4);
        assertThat(ts.hasColumn("email")).isTrue();
        assertThat(ts.hasColumn("nonexistent")).isFalse();
        assertThat(ts.hasIndexOn("email")).isTrue();
        assertThat(ts.hasIndexOn("name")).isFalse();

        String promptContext = schema.toPromptContext();
        assertThat(promptContext).contains("CREATE TABLE users (id INT, name VARCHAR, email VARCHAR, balance DOUBLE);");
        assertThat(promptContext).contains("CREATE UNIQUE INDEX idx_users_email ON users (email);");
    }
}
