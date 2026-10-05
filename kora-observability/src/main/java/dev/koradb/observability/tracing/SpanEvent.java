package dev.koradb.observability.tracing;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A timed event recorded within a span's lifetime.
 */
public record SpanEvent(
        String name,
        long timestampNanos,
        Map<String, Object> attributes
) {
    public SpanEvent {
        Objects.requireNonNull(name, "name must not be null");
        attributes = attributes == null || attributes.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(attributes);
    }

    public static SpanEvent of(String name) {
        return new SpanEvent(name, System.nanoTime(), Collections.emptyMap());
    }

    public static SpanEvent of(String name, Map<String, Object> attributes) {
        return new SpanEvent(name, System.nanoTime(), attributes);
    }
}
