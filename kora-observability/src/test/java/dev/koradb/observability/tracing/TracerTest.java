package dev.koradb.observability.tracing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TracerTest {

    private Tracer tracer;
    private InMemoryTraceExporter exporter;

    @BeforeEach
    void setUp() {
        tracer = Tracer.of("koradb.core");
        exporter = new InMemoryTraceExporter();
        tracer.addExporter(exporter);
    }

    @Test
    @DisplayName("Single span records duration, attributes, and exports on end")
    void testSingleSpan() {
        Span span = tracer.spanBuilder("db.query")
                .setAttribute("db.statement", "SELECT * FROM users")
                .setSpanKind(SpanKind.SERVER)
                .start();

        assertThat(span.getName()).isEqualTo("db.query");
        assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
        assertThat(span.getAttributes()).containsEntry("db.statement", "SELECT * FROM users");
        assertThat(span.isEnded()).isFalse();

        span.end();

        assertThat(span.isEnded()).isTrue();
        assertThat(span.getStatus()).isEqualTo(SpanStatus.OK);
        assertThat(exporter.getFinishedSpans()).hasSize(1);
        assertThat(exporter.getFinishedSpans().get(0)).isSameAs(span);
    }

    @Test
    @DisplayName("Nested spans automatically inherit trace ID and parent span ID")
    void testNestedSpans() {
        Span parent = tracer.startSpan("db.query");
        try (Tracer.Scope parentScope = tracer.withSpan(parent)) {
            assertThat(tracer.currentSpan()).isSameAs(parent);

            Span parseChild = tracer.startSpan("db.parse");
            parseChild.end();

            Span planChild = tracer.startSpan("db.plan");
            planChild.end();

            assertThat(parseChild.getTraceId()).isEqualTo(parent.getTraceId());
            assertThat(parseChild.getParentSpanId()).isEqualTo(parent.getSpanId());

            assertThat(planChild.getTraceId()).isEqualTo(parent.getTraceId());
            assertThat(planChild.getParentSpanId()).isEqualTo(parent.getSpanId());
        } finally {
            parent.end();
        }

        List<Span> finished = exporter.getFinishedSpans();
        assertThat(finished).hasSize(3);
        assertThat(finished.stream().map(Span::getTraceId).distinct()).hasSize(1);
    }

    @Test
    @DisplayName("Span records exception and sets status to ERROR")
    void testExceptionRecording() {
        Span span = tracer.startSpan("db.execute");
        try {
            throw new IllegalArgumentException("Table does not exist");
        } catch (Exception e) {
            span.recordException(e);
        } finally {
            span.end();
        }

        assertThat(span.getStatus()).isEqualTo(SpanStatus.ERROR);
        assertThat(span.getStatusDescription()).contains("Table does not exist");
        assertThat(span.getAttributes()).containsKey("exception.type");
        assertThat(span.getAttributes().get("exception.type")).isEqualTo("java.lang.IllegalArgumentException");
    }

    @Test
    @DisplayName("JsonTraceFormatter formats spans to valid JSON array")
    void testJsonFormatter() {
        Span span = tracer.startSpan("db.test");
        span.setAttribute("row_count", 100);
        span.end();

        String json = JsonTraceFormatter.format(exporter.getFinishedSpans());
        assertThat(json).contains("\"name\": \"db.test\"");
        assertThat(json).contains("\"traceId\": \"" + span.getTraceId().toHex() + "\"");
        assertThat(json).contains("\"row_count\": 100");
    }
}
