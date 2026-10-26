package dev.koradb.sql.parser;

import dev.koradb.KoraDB;
import dev.koradb.sql.ast.ShowStmt;
import dev.koradb.sql.lexer.Lexer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SHOW TABLES / SHOW DATABASES / DESCRIBE in the core parser and executor (not only over TCP). */
class ShowStatementTest {

    private static Object parse(String sql) {
        return new Parser(new Lexer(sql).tokenize()).parse();
    }

    @Test
    void parsesMetadataStatements() {
        assertThat(parse("SHOW TABLES;")).isEqualTo(new ShowStmt(ShowStmt.Kind.TABLES, null));
        assertThat(parse("show databases")).isEqualTo(new ShowStmt(ShowStmt.Kind.DATABASES, null));
        assertThat(parse("DESCRIBE orders")).isEqualTo(new ShowStmt(ShowStmt.Kind.COLUMNS, "orders"));
        assertThat(parse("desc orders;")).isEqualTo(new ShowStmt(ShowStmt.Kind.COLUMNS, "orders"));
    }

    @Test
    void tablesAndDatabasesAreNotReservedWords() {
        assertThat(parse("SELECT tables FROM databases")).isNotNull();
    }

    @Test
    void executesEmbedded() {
        KoraDB db = new KoraDB();
        db.execute("CREATE DATABASE s");
        db.execute("USE s");
        db.execute("CREATE TABLE a (id INT, name VARCHAR)");
        db.execute("CREATE TABLE b (x INT)");
        assertThat(db.execute("SHOW TABLES").rows()).hasSize(2);
        assertThat(db.execute("SHOW DATABASES").columns()).containsExactly("database");
        var cols = db.execute("DESCRIBE a");
        assertThat(cols.rows()).hasSize(2);
        assertThat(cols.rows().get(1).get("data_type")).isEqualTo("VARCHAR");
    }
}
