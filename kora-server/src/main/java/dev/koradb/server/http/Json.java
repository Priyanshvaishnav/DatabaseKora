package dev.koradb.server.http;

import java.util.*;

/**
 * Lightweight, zero-dependency JSON parser and serializer.
 * Uses standard Java collections and supports nested objects, arrays,
 * strings with escape sequences, numbers, booleans, and null values.
 */
public final class Json {

    private Json() {}

    /**
     * Serializes any supported Java object to a JSON string.
     * Supported types: Map, Collection/List, Array, String, Number, Boolean, null.
     */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        serialize(value, sb);
        return sb.toString();
    }

    private static void serialize(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Number n) {
            sb.append(n);
        } else if (value instanceof CharSequence cs) {
            escapeString(cs.toString(), sb);
        } else if (value instanceof Character c) {
            escapeString(c.toString(), sb);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                escapeString(String.valueOf(entry.getKey()), sb);
                sb.append(':');
                serialize(entry.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof Collection<?> coll) {
            sb.append('[');
            boolean first = true;
            for (Object item : coll) {
                if (!first) sb.append(',');
                first = false;
                serialize(item, sb);
            }
            sb.append(']');
        } else if (value.getClass().isArray()) {
            sb.append('[');
            int len = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(',');
                serialize(java.lang.reflect.Array.get(value, i), sb);
            }
            sb.append(']');
        } else {
            // Fallback: serialize as string
            escapeString(value.toString(), sb);
        }
    }

    /**
     * Escapes a string for JSON compliance and wraps it in double quotes.
     */
    public static void escapeString(String s, StringBuilder sb) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * Escapes a string for JSON compliance and returns the quoted string.
     */
    public static String escapeString(String s) {
        StringBuilder sb = new StringBuilder();
        escapeString(s, sb);
        return sb.toString();
    }

    /**
     * Parses a JSON string into Java objects (Map, List, String, Number, Boolean, null).
     */
    public static Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return new Parser(json.trim()).parse();
    }

    /**
     * Parses a JSON string and verifies it is a JSON object (returns Map).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object val = parse(json);
        if (val == null) {
            return Collections.emptyMap();
        }
        if (val instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Expected JSON object but got: " + val.getClass().getSimpleName());
    }

    private static final class Parser {
        private final String src;
        private final int len;
        private int pos = 0;

        Parser(String src) {
            this.src = src;
            this.len = src.length();
        }

        Object parse() {
            skipWhitespace();
            Object result = parseValue();
            skipWhitespace();
            if (pos < len) {
                throw new IllegalArgumentException("Unexpected trailing character at index " + pos + ": " + src.charAt(pos));
            }
            return result;
        }

        private void skipWhitespace() {
            while (pos < len) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= len) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> {
                    if (c == '-' || Character.isDigit(c)) {
                        yield parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected character at index " + pos + ": " + c);
                }
            };
        }

        private Map<String, Object> parseObject() {
            match('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (pos < len && src.charAt(pos) == '}') {
                pos++;
                return map;
            }

            while (pos < len) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                match(':');
                skipWhitespace();
                Object val = parseValue();
                map.put(key, val);
                skipWhitespace();
                if (pos < len && src.charAt(pos) == ',') {
                    pos++;
                } else if (pos < len && src.charAt(pos) == '}') {
                    pos++;
                    return map;
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}' in object at index " + pos);
                }
            }
            throw new IllegalArgumentException("Unterminated object in JSON");
        }

        private List<Object> parseArray() {
            match('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (pos < len && src.charAt(pos) == ']') {
                pos++;
                return list;
            }

            while (pos < len) {
                skipWhitespace();
                Object val = parseValue();
                list.add(val);
                skipWhitespace();
                if (pos < len && src.charAt(pos) == ',') {
                    pos++;
                } else if (pos < len && src.charAt(pos) == ']') {
                    pos++;
                    return list;
                } else {
                    throw new IllegalArgumentException("Expected ',' or ']' in array at index " + pos);
                }
            }
            throw new IllegalArgumentException("Unterminated array in JSON");
        }

        private String parseString() {
            match('"');
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= len) {
                        throw new IllegalArgumentException("Unterminated escape sequence in string");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > len) {
                                throw new IllegalArgumentException("Invalid unicode escape in string");
                            }
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new IllegalArgumentException("Invalid escape sequence: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string in JSON");
        }

        private Number parseNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') {
                pos++;
            }
            while (pos < len && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
            boolean isFloating = false;
            if (pos < len && src.charAt(pos) == '.') {
                isFloating = true;
                pos++;
                while (pos < len && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < len && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                isFloating = true;
                pos++;
                if (pos < len && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < len && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            String numStr = src.substring(start, pos);
            if (isFloating) {
                return Double.parseDouble(numStr);
            }
            try {
                return Long.parseLong(numStr);
            } catch (NumberFormatException e) {
                return new java.math.BigInteger(numStr);
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
            throw new IllegalArgumentException("Invalid boolean literal at index " + pos);
        }

        private Object parseNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid null literal at index " + pos);
        }

        private void match(char expected) {
            if (pos >= len || src.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at index " + pos +
                        (pos < len ? " but found '" + src.charAt(pos) + "'" : " but reached end of input"));
            }
            pos++;
        }
    }
}
