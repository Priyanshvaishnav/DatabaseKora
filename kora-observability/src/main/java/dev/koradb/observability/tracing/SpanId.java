package dev.koradb.observability.tracing;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 64-bit OpenTelemetry/W3C compliant span identifier represented as a 16-character hexadecimal string.
 */
public final class SpanId implements Comparable<SpanId> {

    public static final String INVALID_HEX = "0000000000000000";
    private static final SpanId INVALID = new SpanId(INVALID_HEX);

    private final String hex;

    private SpanId(String hex) {
        this.hex = hex;
    }

    public static SpanId invalid() {
        return INVALID;
    }

    public static SpanId generate() {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        long val = rand.nextLong();
        while (val == 0) {
            val = rand.nextLong();
        }
        return new SpanId(String.format("%016x", val));
    }

    public static SpanId fromHex(String hex) {
        Objects.requireNonNull(hex, "hex must not be null");
        String trimmed = hex.trim().toLowerCase();
        if (trimmed.length() != 16) {
            throw new IllegalArgumentException("SpanId hex must be exactly 16 characters, got: " + hex);
        }
        for (int i = 0; i < 16; i++) {
            char c = trimmed.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                throw new IllegalArgumentException("Invalid hex character in SpanId: " + c);
            }
        }
        if (INVALID_HEX.equals(trimmed)) {
            return INVALID;
        }
        return new SpanId(trimmed);
    }

    public String toHex() {
        return hex;
    }

    public boolean isValid() {
        return !INVALID_HEX.equals(hex);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SpanId spanId)) return false;
        return Objects.equals(hex, spanId.hex);
    }

    @Override
    public int hashCode() {
        return hex.hashCode();
    }

    @Override
    public int compareTo(SpanId o) {
        return this.hex.compareTo(o.hex);
    }

    @Override
    public String toString() {
        return hex;
    }
}
