package dev.koradb.observability.tracing;

/**
 * Consumer interface for completed spans.
 */
@FunctionalInterface
public interface TraceExporter {

    /**
     * Called when a span has ended.
     */
    void export(Span span);
}
