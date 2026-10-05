package dev.koradb.observability.tracing;

import java.util.Collection;
import java.util.Map;

/**
 * Serializes spans into standard OpenTelemetry-compatible JSON.
 */
public final class JsonTraceFormatter {

    private JsonTraceFormatter() {}

    public static String format(Collection<Span> spans) {
        if (spans == null || spans.isEmpty()) {
            return "[]";
        }

        StringBuilder sb = new StringBuilder("[\n");
        boolean first = true;
        for (Span span : spans) {
            if (!first) sb.append(",\n");
            formatSpan(sb, span);
            first = false;
        }
        sb.append("\n]");
        return sb.toString();
    }

    private static void formatSpan(StringBuilder sb, Span span) {
        sb.append("  {\n");
        sb.append("    \"traceId\": \"").append(span.getTraceId().toHex()).append("\",\n");
        sb.append("    \"spanId\": \"").append(span.getSpanId().toHex()).append("\",\n");
        if (span.getParentSpanId() != null) {
            sb.append("    \"parentSpanId\": \"").append(span.getParentSpanId().toHex()).append("\",\n");
        }
        sb.append("    \"name\": \"").append(escapeJson(span.getName())).append("\",\n");
        sb.append("    \"kind\": \"").append(span.getKind().name()).append("\",\n");
        sb.append("    \"startTimeUnixNano\": ").append(span.getStartTimeNanos()).append(",\n");
        sb.append("    \"endTimeUnixNano\": ").append(span.getEndTimeNanos()).append(",\n");
        sb.append("    \"durationMicros\": ").append(span.getDurationMicros()).append(",\n");
        sb.append("    \"status\": {\n");
        sb.append("      \"code\": \"").append(span.getStatus().name()).append("\"");
        if (!span.getStatusDescription().isEmpty()) {
            sb.append(",\n      \"message\": \"").append(escapeJson(span.getStatusDescription())).append("\"");
        }
        sb.append("\n    },\n");

        // Attributes
        sb.append("    \"attributes\": {");
        boolean firstAttr = true;
        for (Map.Entry<String, Object> entry : span.getAttributes().entrySet()) {
            if (!firstAttr) sb.append(", ");
            sb.append('"').append(escapeJson(entry.getKey())).append("\": ");
            Object val = entry.getValue();
            if (val instanceof Number || val instanceof Boolean) {
                sb.append(val);
            } else {
                sb.append('"').append(escapeJson(String.valueOf(val))).append('"');
            }
            firstAttr = false;
        }
        sb.append("}\n");
        sb.append("  }");
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
