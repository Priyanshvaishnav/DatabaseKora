package dev.koradb.observability.tracing;

/**
 * OpenTelemetry span kinds.
 */
public enum SpanKind {
    INTERNAL,
    SERVER,
    CLIENT,
    PRODUCER,
    CONSUMER
}
