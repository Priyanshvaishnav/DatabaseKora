package dev.koradb.cli;

import dev.koradb.client.ClientQueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TableFormatterTest {

    @Test
    @DisplayName("Should format DDL result")
    void testFormatDdl() {
        ClientQueryResult ddl = new ClientQueryResult(false, 0, "OK", List.of(), List.of(), 5L);
        String formatted = TableFormatter.format(ddl);
        assertThat(formatted).contains("Query OK (5.00 ms)");
    }

    @Test
    @DisplayName("Should format DML result with affected rows")
    void testFormatDml() {
        ClientQueryResult dml = new ClientQueryResult(false, 3, "OK", List.of(), List.of(), 12L);
        String formatted = TableFormatter.format(dml);
        assertThat(formatted).contains("Query OK, 3 row(s) affected (12.00 ms)");
    }

    @Test
    @DisplayName("Should format SELECT results as pretty ASCII table")
    void testFormatSelectTable() {
        List<String> cols = List.of("id", "name", "balance");
        List<List<Object>> rows = List.of(
                Arrays.asList(1, "Alice", 1000.5),
                Arrays.asList(2, "Bob", null)
        );
        ClientQueryResult res = new ClientQueryResult(true, 0, "OK", cols, rows, 4L);

        String formatted = TableFormatter.format(res);
        assertThat(formatted).contains("+----+-------+---------+");
        assertThat(formatted).contains("| id | name  | balance |");
        assertThat(formatted).contains("|  1 | Alice |  1000.5 |");
        assertThat(formatted).contains("|  2 | Bob   | NULL    |");
        assertThat(formatted).contains("2 rows in set (4.00 ms)");
    }
}
