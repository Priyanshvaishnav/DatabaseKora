package dev.koradb.ai.util;

import java.util.*;

/**
 * Minimalist, zero-dependency JSON parser and serializer built specifically for LLM API payloads.
 *
 * <p>Parses JSON text into standard Java collections:
 * <ul>
 *   <li>Objects &rarr; {@code Map<String, Object>}</li>
 *   <li>Arrays &rarr; {@code List<Object>}</li>
 *   <li>Strings &rarr; {@code String}</li>
 *   <li>Numbers &rarr; {@code Long} or {@code Double}</li>
 *   <li>Booleans &rarr; {@code Boolean}</li>
 *   <li>Nulls &rarr; {@code null}</li>
 * </ul>
 */
public final class SimpleJson {

    private SimpleJson() {}

    // =========================================================================
    // Serialization
    // =========================================================================

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        serialize(value, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void serialize(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append('"').append(escape(s)).append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(',');
                sb.append('"').append(escape(String.valueOf(entry.getKey()))).append("\":");
                serialize(entry.getValue(), sb);
                first = false;
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> iter) {
            sb.append('[');
            boolean first = true;
            for (Object item : iter) {
                if (!first) sb.append(',');
                serialize(item, sb);
                first = false;
            }
            sb.append(']');
        } else if (value.getClass().isArray()) {
            sb.append('[');
            Object[] array = (Object[]) value;
            for (int i = 0; i < array.length; i++) {
                if (i > 0) sb.append(',');
                serialize(array[i], sb);
            }
            sb.append(']');
        } else {
            // Fallback: serialize as string
            sb.append('"').append(escape(value.toString())).append('"');
        }
    }

    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 32 || c >= 127) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // Deserialization / Parsing
    // =========================================================================

    public static Object parse(String json) {
        if (json == null || json.isBlank()) return null;
        Parser parser = new Parser(json);
        return parser.parseValue();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object result = parse(json);
        if (result instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Expected JSON object but got: " + (result != null ? result.getClass().getSimpleName() : "null"));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String json) {
        Object result = parse(json);
        if (result instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new IllegalArgumentException("Expected JSON array but got: " + (result != null ? result.getClass().getSimpleName() : "null"));
    }

    // =========================================================================
    // Navigation Helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    public static Object getByPath(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isEmpty()) return null;
        String[] parts = path.split("\\.");
        Object current = root;
        for (String part : parts) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else if (current instanceof List<?> list) {
                try {
                    int idx = Integer.parseInt(part);
                    current = (idx >= 0 && idx < list.size()) ? list.get(idx) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
            if (current == null) return null;
        }
        return current;
    }

    public static String getString(Map<String, Object> root, String path) {
        Object v = getByPath(root, path);
        return v != null ? String.valueOf(v) : null;
    }

    public static Long getLong(Map<String, Object> root, String path) {
        Object v = getByPath(root, path);
        if (v instanceof Number n) return n.longValue();
        return null;
    }

    public static Integer getInt(Map<String, Object> root, String path) {
        Object v = getByPath(root, path);
        if (v instanceof Number n) return n.intValue();
        return null;
    }

    // =========================================================================
    // Internal Recursive Descent Parser
    // =========================================================================

    private static final class Parser {
        private final String src;
        private int pos;
        private final int len;

        Parser(String src) {
            this.src = src;
            this.len = src.length();
            this.pos = 0;
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= len) return null;
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + pos);
                }
            };
        }

        private Map<String, Object> parseObject() {
            consume('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                consume('}');
                return map;
            }

            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                consume(':');
                Object val = parseValue();
                map.put(key, val);

                skipWhitespace();
                char next = peek();
                if (next == '}') {
                    consume('}');
                    break;
                } else if (next == ',') {
                    consume(',');
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}' in object, found '" + next + "' at " + pos);
                }
            }
            return map;
        }

        private List<Object> parseArray() {
            consume('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                consume(']');
                return list;
            }

            while (true) {
                Object val = parseValue();
                list.add(val);

                skipWhitespace();
                char next = peek();
                if (next == ']') {
                    consume(']');
                    break;
                } else if (next == ',') {
                    consume(',');
                } else {
                    throw new IllegalArgumentException("Expected ',' or ']' in array, found '" + next + "' at " + pos);
                }
            }
            return list;
        }

        private String parseString() {
            consume('"');
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                } else if (c == '\\') {
                    if (pos >= len) throw new IllegalArgumentException("Unterminated escape at end of string");
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/'  -> sb.append('/');
                        case 'b'  -> sb.append('\b');
                        case 'f'  -> sb.append('\f');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        case 'u'  -> {
                            if (pos + 4 > len) throw new IllegalArgumentException("Invalid unicode escape");
                            String hex = src.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string literal");
        }

        private Number parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < len && Character.isDigit(src.charAt(pos))) pos++;
            boolean isDecimal = false;
            if (peek() == '.') {
                isDecimal = true;
                pos++;
                while (pos < len && Character.isDigit(src.charAt(pos))) pos++;
            }
            if (peek() == 'e' || peek() == 'E') {
                isDecimal = true;
                pos++;
                if (peek() == '+' || peek() == '-') pos++;
                while (pos < len && Character.isDigit(src.charAt(pos))) pos++;
            }
            String numStr = src.substring(start, pos);
            if (isDecimal) {
                return Double.parseDouble(numStr);
            } else {
                try {
                    return Long.parseLong(numStr);
                } catch (NumberFormatException e) {
                    return Double.parseDouble(numStr);
                }
            }
        }

        private Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            } else if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid boolean literal at " + pos);
        }

        private Object parseNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid null literal at " + pos);
        }

        private void skipWhitespace() {
            while (pos < len) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            return pos < len ? src.charAt(pos) : '\0';
        }

        private void consume(char expected) {
            skipWhitespace();
            if (pos >= len || src.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' but got '" + peek() + "' at position " + pos);
            }
            pos++;
        }
    }
}
