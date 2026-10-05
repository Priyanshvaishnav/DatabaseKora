package dev.koradb.observability.tracing;

import java.util.Objects;

/**
 * Immutable context associated with a span, containing identifiers and W3C trace flags.
 * Supports W3C TraceContext traceparent serialization for distributed tracing propagation.
 */
public record SpanContext(
        TraceId traceId,
        SpanId spanId,
        byte traceFlags
) {
    public static final byte FLAG_SAMPLED = 0x01;
    public static final byte FLAG_DEFAULT = 0x01;

    public SpanContext {
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(spanId, "spanId must not be null");
    }

    public static SpanContext create(TraceId traceId, SpanId spanId) {
        return new SpanContext(traceId, spanId, FLAG_DEFAULT);
    }

    public static SpanContext create(TraceId traceId, SpanId spanId, byte traceFlags) {
        return new SpanContext(traceId, spanId, traceFlags);
    }

    public boolean isSampled() {
        return (traceFlags & FLAG_SAMPLED) != 0;
    }

    public boolean isValid() {
        return traceId.isValid() && spanId.isValid();
    }

    /**
     * Serializes this context to a W3C traceparent header string (e.g. 00-traceId-spanId-01).
     */
    public String toTraceParent() {
        return String.format("00-%s-%s-%02x", traceId.toHex(), spanId.toHex(), traceFlags);
    }

    /**
     * Parses a W3C traceparent header string into a SpanContext.
     */
    public static SpanContext fromTraceParent(String header) {
        if (header == null || header.isBlank()) {
            throw new IllegalArgumentException("traceparent header must not be null or blank");
        }
        String[] parts = header.trim().split("-");
        if (parts.length < 4) {
            throw new IllegalArgumentException("Invalid traceparent format (expected 4 dash-separated parts): " + header);
        }
        // version: parts[0]
        TraceId tid = TraceId.fromHex(parts[1]);
        SpanId sid = SpanId.fromHex(parts[2]);
        byte flags;
        try {
            flags = (byte) Integer.parseInt(parts[3], 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid traceparent flags: " + parts[3], e);
        }
        return new SpanContext(tid, sid, flags);
    }
}
