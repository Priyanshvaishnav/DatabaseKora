package dev.koradb.observability.tracing;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * An in-memory trace exporter that buffers completed spans for inspection, testing, and profiling.
 */
public final class InMemoryTraceExporter implements TraceExporter {

    private final List<Span> finishedSpans = new CopyOnWriteArrayList<>();

    @Override
    public void export(Span span) {
        if (span != null) {
            finishedSpans.add(span);
        }
    }

    public List<Span> getFinishedSpans() {
        return Collections.unmodifiableList(finishedSpans);
    }

    public List<Span> findByTraceId(TraceId traceId) {
        return finishedSpans.stream()
                .filter(s -> s.getTraceId().equals(traceId))
                .collect(Collectors.toList());
    }

    public List<Span> findByName(String name) {
        return finishedSpans.stream()
                .filter(s -> s.getName().equals(name))
                .collect(Collectors.toList());
    }

    public void clear() {
        finishedSpans.clear();
    }
}
