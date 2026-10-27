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
                .isEqualTo("delete from sessions where expired = true;");
    }

    @Test
    @DisplayName("Preserve pre-existing SQL queries unchanged (string literals keep their case)")
    void testRawSqlPassthrough() {
        assertThat(client.translateTextToSql("SELECT id, name FROM users WHERE id = 10;"))
                .isEqualTo("SELECT id, name FROM users WHERE id = 10;");
        assertThat(client.translateTextToSql("select * from customers where name = 'alice'"))
                .isEqualTo("select * from customers where name = 'alice';");
        // Without semicolon — one is added
        assertThat(client.translateTextToSql("SELECT * FROM orders"))
                .isEqualTo("SELECT * FROM orders;");
        // SHOW statements
        assertThat(client.translateTextToSql("SHOW TABLES;"))
                .isEqualTo("SHOW TABLES;");
        assertThat(client.translateTextToSql("show tables"))
                .isEqualTo("SHOW TABLES;");
        assertThat(client.translateTextToSql("show databases"))
                .isEqualTo("SHOW DATABASES;");
        assertThat(client.translateTextToSql("show all tables"))
                .isEqualTo("SHOW TABLES;");
        assertThat(client.translateTextToSql("list tables"))
                .isEqualTo("SHOW TABLES;");
        assertThat(client.translateTextToSql("show buffer pool status"))
                .isEqualTo("SHOW BUFFER POOL STATUS;");
        assertThat(client.translateTextToSql("describe products"))
                .isEqualTo("DESCRIBE PRODUCTS;");
    }

    @Test
    @DisplayName("Translate free-form natural language with English comparison phrases")
    void testFreeFormNaturalLanguage() {
        // "cheaper than" → price < N  (user-reported bug case)
        assertThat(client.translateTextToSql("show me all products cheaper than 200"))
                .isEqualTo("SELECT * FROM products WHERE price < 200;");

        // "older than" — no column before the phrase: the phrase itself means age
        assertThat(client.translateTextToSql("get users older than 25"))
                .isEqualTo("SELECT * FROM users WHERE age > 25;");

        // "more than" with explicit column before it
        assertThat(client.translateTextToSql("find orders with total more than 500"))
                .isEqualTo("SELECT * FROM orders WHERE total > 500;");

        // "less than" with explicit column — structured WHERE pattern matches (salary < 50000)
        assertThat(client.translateTextToSql("list employees where salary less than 50000"))
                .isEqualTo("SELECT * FROM employees WHERE salary < 50000;");

        // "above"
        assertThat(client.translateTextToSql("show items where stock above 100"))
                .isEqualTo("SELECT * FROM items WHERE stock > 100;");

        // "below"
        assertThat(client.translateTextToSql("get products where price below 50"))
                .isEqualTo("SELECT * FROM products WHERE price < 50;");

        // plain "show me all <table>" with filler words
        assertThat(client.translateTextToSql("show me all products"))
                .isEqualTo("SELECT * FROM products;");
    }

    @Test
    @DisplayName("Return clear error comment when prompt cannot be translated")
    void testFallbackMessage() {
        String result = client.translateTextToSql("xzq");
        // Should NOT concatenate garbage into a table name
        assertThat(result).startsWith("-- Could not translate:");
        assertThat(result).doesNotContain("SELECT * FROM xzq");
    }
}
