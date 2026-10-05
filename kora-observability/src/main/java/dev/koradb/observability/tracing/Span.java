package dev.koradb.observability.tracing;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * An OpenTelemetry-compatible span representing a single operation within a trace.
 * Implements {@link AutoCloseable} to permit usage in try-with-resources blocks.
 */
public final class Span implements AutoCloseable {

    private final String name;
    private final SpanContext context;
    private final SpanId parentSpanId;
    private final SpanKind kind;
    private final long startTimeNanos;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final List<SpanEvent> events = new CopyOnWriteArrayList<>();
    private final Consumer<Span> onEndCallback;

    private volatile long endTimeNanos;
    private volatile SpanStatus status = SpanStatus.UNSET;
    private volatile String statusDescription = "";
    private volatile boolean ended = false;

    public Span(String name,
                SpanContext context,
                SpanId parentSpanId,
                SpanKind kind,
                long startTimeNanos,
                Consumer<Span> onEndCallback) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.parentSpanId = parentSpanId;
        this.kind = kind == null ? SpanKind.INTERNAL : kind;
        this.startTimeNanos = startTimeNanos <= 0 ? System.nanoTime() : startTimeNanos;
        this.onEndCallback = onEndCallback;
    }

    public String getName() {
        return name;
    }

    public SpanContext getContext() {
        return context;
    }

    public TraceId getTraceId() {
        return context.traceId();
    }

    public SpanId getSpanId() {
        return context.spanId();
    }

    public SpanId getParentSpanId() {
        return parentSpanId;
    }

    public SpanKind getKind() {
        return kind;
    }

    public long getStartTimeNanos() {
        return startTimeNanos;
    }

    public long getEndTimeNanos() {
        return endTimeNanos;
    }

    public SpanStatus getStatus() {
        return status;
    }

    public String getStatusDescription() {
        return statusDescription;
    }

    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public List<SpanEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public boolean isEnded() {
        return ended;
    }

    public long getDurationNanos() {
        if (!ended) {
            return System.nanoTime() - startTimeNanos;
        }
        return endTimeNanos - startTimeNanos;
    }

    public long getDurationMicros() {
        return getDurationNanos() / 1_000L;
    }

    public long getDurationMillis() {
        return getDurationNanos() / 1_000_000L;
    }

    public Span setAttribute(String key, Object value) {
        if (key != null && value != null) {
            attributes.put(key, value);
        }
        return this;
    }

    public Span addEvent(String eventName) {
        events.add(SpanEvent.of(eventName));
        return this;
    }

    public Span addEvent(String eventName, Map<String, Object> eventAttributes) {
        events.add(SpanEvent.of(eventName, eventAttributes));
        return this;
    }

    public Span setStatus(SpanStatus status) {
        return setStatus(status, "");
    }

    public Span setStatus(SpanStatus status, String description) {
        this.status = status;
        this.statusDescription = description == null ? "" : description;
        return this;
    }

    public Span recordException(Throwable throwable) {
        if (throwable != null) {
            setStatus(SpanStatus.ERROR, throwable.getMessage());
            setAttribute("exception.type", throwable.getClass().getName());
            if (throwable.getMessage() != null) {
                setAttribute("exception.message", throwable.getMessage());
            }
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            setAttribute("exception.stacktrace", sw.toString());
        }
        return this;
    }

    /**
     * Completes this span, recording its end timestamp and notifying listeners.
     */
    public synchronized void end() {
        if (ended) {
            return;
        }
        endTimeNanos = System.nanoTime();
        if (status == SpanStatus.UNSET) {
            status = SpanStatus.OK;
        }
        ended = true;
        if (onEndCallback != null) {
            try {
                onEndCallback.accept(this);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void close() {
        end();
    }

    @Override
    public String toString() {
        return String.format("Span{name='%s', traceId=%s, spanId=%s, parentSpanId=%s, status=%s, durationMs=%d}",
                name, getTraceId(), getSpanId(), parentSpanId, status, getDurationMillis());
    }
}
