package dev.koradb.server.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTest {

    @Test
    @DisplayName("Should serialize primitives, lists, maps, and nulls correctly")
    void testSerialization() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", "KoraDB");
        map.put("version", 1);
        map.put("active", true);
        map.put("score", 98.6);
        map.put("tags", List.of("db", "sql", "java"));
        map.put("extra", null);

        String json = Json.stringify(map);
        assertThat(json).isEqualTo("{\"name\":\"KoraDB\",\"version\":1,\"active\":true,\"score\":98.6,\"tags\":[\"db\",\"sql\",\"java\"],\"extra\":null}");
    }

    @Test
    @DisplayName("Should properly escape special characters in strings")
    void testEscapes() {
        String input = "Line 1\nLine 2\t\"quoted\" and \\backslash\\";
        String json = Json.stringify(input);
        assertThat(json).isEqualTo("\"Line 1\\nLine 2\\t\\\"quoted\\\" and \\\\backslash\\\\\"");

        Object parsed = Json.parse(json);
        assertThat(parsed).isEqualTo(input);
    }

    @Test
    @DisplayName("Should parse complex JSON object with arrays and numbers")
    void testParsing() {
        String json = """
            {
              "sql": "SELECT * FROM users WHERE id = 10",
              "database": "prod_db",
              "limit": 50,
              "cache": false,
              "threshold": 3.1415,
              "params": ["a", 42, null]
            }
            """;

        Map<String, Object> obj = Json.parseObject(json);
        assertThat(obj.get("sql")).isEqualTo("SELECT * FROM users WHERE id = 10");
        assertThat(obj.get("database")).isEqualTo("prod_db");
        assertThat(obj.get("limit")).isEqualTo(50L);
        assertThat(obj.get("cache")).isEqualTo(false);
        assertThat(obj.get("threshold")).isEqualTo(3.1415);

        @SuppressWarnings("unchecked")
        List<Object> params = (List<Object>) obj.get("params");
        assertThat(params).containsExactly("a", 42L, null);
    }

    @Test
    @DisplayName("Should reject malformed JSON syntax")
    void testMalformed() {
        assertThatThrownBy(() -> Json.parse("{invalid"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Json.parse("[1, 2,"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Json.parseObject("[\"array\"]"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
