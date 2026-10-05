package dev.koradb.observability.tracing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class W3CTraceContextTest {

    @Test
    @DisplayName("Serializes and parses standard W3C traceparent header")
    void testW3CSerializationAndParsing() {
        TraceId tid = TraceId.fromHex("4bf92f3577b34da6a3ce929d0e0e4736");
        SpanId sid = SpanId.fromHex("00f067aa0ba902b7");
        SpanContext ctx = SpanContext.create(tid, sid, (byte) 0x01);

        String traceparent = ctx.toTraceParent();
        assertThat(traceparent).isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        SpanContext parsed = SpanContext.fromTraceParent(traceparent);
        assertThat(parsed.traceId()).isEqualTo(tid);
        assertThat(parsed.spanId()).isEqualTo(sid);
        assertThat(parsed.traceFlags()).isEqualTo((byte) 0x01);
        assertThat(parsed.isSampled()).isTrue();
    }

    @Test
    @DisplayName("Throws exception on invalid traceparent strings")
    void testInvalidTraceParent() {
        assertThatThrownBy(() -> SpanContext.fromTraceParent("invalid-header"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> SpanContext.fromTraceParent("00-short-00f067aa0ba902b7-01"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
