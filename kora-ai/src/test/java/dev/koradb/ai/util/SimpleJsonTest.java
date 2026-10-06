package dev.koradb.ai.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SimpleJsonTest {

    @Test
    @DisplayName("Parse and serialize complex nested JSON object")
    void testParseAndSerializeObject() {
        String json = """
                {
                  "model": "gpt-4o",
                  "temperature": 0.2,
                  "stream": false,
                  "count": 42,
                  "messages": [
                    {"role": "system", "content": "You are a SQL expert."},
                    {"role": "user", "content": "SELECT * FROM users"}
                  ],
                  "options": {
                    "debug": true,
                    "comment": null
                  }
                }
                """;

        Map<String, Object> map = SimpleJson.parseObject(json);
        assertThat(map.get("model")).isEqualTo("gpt-4o");
        assertThat(map.get("stream")).isEqualTo(false);
        assertThat(map.get("count")).isEqualTo(42L);
        assertThat(map.get("temperature")).isEqualTo(0.2);

        assertThat(SimpleJson.getString(map, "model")).isEqualTo("gpt-4o");
        assertThat(SimpleJson.getString(map, "messages.0.role")).isEqualTo("system");
        assertThat(SimpleJson.getString(map, "messages.1.content")).isEqualTo("SELECT * FROM users");
        assertThat(SimpleJson.getByPath(map, "options.debug")).isEqualTo(true);
        assertThat(SimpleJson.getByPath(map, "options.comment")).isNull();

        String serialized = SimpleJson.stringify(map);
        assertThat(serialized).contains("\"model\":\"gpt-4o\"");
        assertThat(serialized).contains("\"temperature\":0.2");
        assertThat(serialized).contains("\"debug\":true");
    }

    @Test
    @DisplayName("Escape and unescape special characters")
    void testEscapes() {
        String original = "Hello \"World\"\nLine 2\tTabbed\\Backslash";
        String escaped = SimpleJson.escape(original);
        assertThat(escaped).contains("\\\"");
        assertThat(escaped).contains("\\n");
        assertThat(escaped).contains("\\t");
        assertThat(escaped).contains("\\\\");

        String json = "{\"text\": \"" + escaped + "\"}";
        Map<String, Object> parsed = SimpleJson.parseObject(json);
        assertThat(parsed.get("text")).isEqualTo(original);
    }
}
