package dev.koradb.ai.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedLlmClientTest {

    private final RuleBasedLlmClient client = new RuleBasedLlmClient();

    @Test
    @DisplayName("Translate count queries")
    void testCountQueries() {
        assertThat(client.translateTextToSql("how many accounts are there"))
                .isEqualTo("SELECT COUNT(*) FROM accounts;");
        assertThat(client.translateTextToSql("count users"))
                .isEqualTo("SELECT COUNT(*) FROM users;");
    }

    @Test
    @DisplayName("Translate show all queries")
    void testShowAllQueries() {
        assertThat(client.translateTextToSql("show all users"))
                .isEqualTo("SELECT * FROM users;");
        assertThat(client.translateTextToSql("list all products"))
                .isEqualTo("SELECT * FROM products;");
    }

    @Test
    @DisplayName("Translate filter where queries")
    void testWhereQueries() {
        assertThat(client.translateTextToSql("find products where price > 50"))
                .isEqualTo("SELECT * FROM products WHERE price > 50;");
        assertThat(client.translateTextToSql("get users where active is true"))
                .isEqualTo("SELECT * FROM users WHERE active = true;");
        assertThat(client.translateTextToSql("show customers where name equals Alice"))
                .isEqualTo("SELECT * FROM customers WHERE name = 'Alice';");
    }

    @Test
    @DisplayName("Translate top N queries")
    void testTopNQueries() {
        assertThat(client.translateTextToSql("top 10 items by sales desc"))
                .isEqualTo("SELECT * FROM items ORDER BY sales DESC LIMIT 10;");
        assertThat(client.translateTextToSql("show top 5 employees by salary"))
                .isEqualTo("SELECT * FROM employees ORDER BY salary DESC LIMIT 5;");
    }

    @Test
    @DisplayName("Translate aggregate and group by queries")
    void testAggregatesAndGroupBy() {
        assertThat(client.translateTextToSql("calculate avg salary in employees"))
                .isEqualTo("SELECT AVG(salary) FROM employees;");
        assertThat(client.translateTextToSql("group orders by status"))
                .isEqualTo("SELECT status, COUNT(*) FROM orders GROUP BY status;");
    }

    @Test
    @DisplayName("Translate delete queries")
    void testDeleteQueries() {
        assertThat(client.translateTextToSql("delete from sessions where expired = true"))
                .isEqualTo("DELETE FROM sessions WHERE expired = true;");
    }

    @Test
    @DisplayName("Preserve pre-existing SQL queries")
    void testRawSqlPassthrough() {
        String sql = "SELECT id, name FROM users WHERE id = 10;";
        assertThat(client.translateTextToSql(sql)).isEqualTo(sql);
    }
}
