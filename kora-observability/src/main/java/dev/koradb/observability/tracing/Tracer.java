package dev.koradb.observability.tracing;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * OpenTelemetry-compatible Tracer managing span lifecycle, context propagation, and active span hierarchies.
 */
public final class Tracer {

    private final String instrumentationScope;
    private final List<TraceExporter> exporters = new CopyOnWriteArrayList<>();
    private final ThreadLocal<Span> activeSpanThreadLocal = new ThreadLocal<>();

    public Tracer(String instrumentationScope) {
        this.instrumentationScope = Objects.requireNonNull(instrumentationScope, "instrumentationScope must not be null");
    }

    public static Tracer of(String instrumentationScope) {
        return new Tracer(instrumentationScope);
    }

    public void addExporter(TraceExporter exporter) {
        if (exporter != null) {
            exporters.add(exporter);
        }
    }

    public void removeExporter(TraceExporter exporter) {
        if (exporter != null) {
            exporters.remove(exporter);
        }
    }

    public Span currentSpan() {
        return activeSpanThreadLocal.get();
    }

    /**
     * Sets the given span as active on the current thread, returning an AutoCloseable Scope.
     */
    public Scope withSpan(Span span) {
        Span previous = activeSpanThreadLocal.get();
        activeSpanThreadLocal.set(span);
        return () -> activeSpanThreadLocal.set(previous);
    }

    /**
     * Starts a new span builder for creating a span.
     */
    public SpanBuilder spanBuilder(String spanName) {
        return new SpanBuilder(spanName);
    }

    /**
     * Convenience method to start and immediately return a span.
     */
    public Span startSpan(String spanName) {
        return spanBuilder(spanName).start();
    }

    private void onSpanEnd(Span span) {
        for (TraceExporter exporter : exporters) {
            try {
                exporter.export(span);
            } catch (Exception ignored) {}
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public final class SpanBuilder {
        private final String spanName;
        private SpanContext parentContext;
        private SpanKind kind = SpanKind.INTERNAL;
        private final Map<String, Object> attributes = new HashMap<>();

        private SpanBuilder(String spanName) {
            this.spanName = Objects.requireNonNull(spanName, "spanName must not be null");
        }

        public SpanBuilder setParent(SpanContext parentContext) {
            this.parentContext = parentContext;
            return this;
        }

        public SpanBuilder setSpanKind(SpanKind kind) {
            this.kind = kind == null ? SpanKind.INTERNAL : kind;
            return this;
        }

        public SpanBuilder setAttribute(String key, Object value) {
            if (key != null && value != null) {
                this.attributes.put(key, value);
            }
            return this;
        }

        public Span start() {
            TraceId traceId;
            SpanId parentSpanId;
            byte flags = SpanContext.FLAG_DEFAULT;

            if (parentContext != null && parentContext.isValid()) {
                traceId = parentContext.traceId();
                parentSpanId = parentContext.spanId();
                flags = parentContext.traceFlags();
            } else {
                Span active = activeSpanThreadLocal.get();
                if (active != null) {
                    traceId = active.getTraceId();
                    parentSpanId = active.getSpanId();
                    flags = active.getContext().traceFlags();
                } else {
                    traceId = TraceId.generate();
                    parentSpanId = null;
                }
            }

            SpanId spanId = SpanId.generate();
            SpanContext context = SpanContext.create(traceId, spanId, flags);

            Span span = new Span(spanName, context, parentSpanId, kind, System.nanoTime(), Tracer.this::onSpanEnd);
            span.setAttribute("instrumentation.scope", instrumentationScope);
            for (Map.Entry<String, Object> e : attributes.entrySet()) {
                span.setAttribute(e.getKey(), e.getValue());
            }

            return span;
        }
    }
}
