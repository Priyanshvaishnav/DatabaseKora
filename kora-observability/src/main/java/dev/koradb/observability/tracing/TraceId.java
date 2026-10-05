package dev.koradb.observability.tracing;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 128-bit OpenTelemetry/W3C compliant trace identifier represented as a 32-character hexadecimal string.
 */
public final class TraceId implements Comparable<TraceId> {

    public static final String INVALID_HEX = "00000000000000000000000000000000";
    private static final TraceId INVALID = new TraceId(INVALID_HEX);

    private final String hex;

    private TraceId(String hex) {
        this.hex = hex;
    }

    public static TraceId invalid() {
        return INVALID;
    }

    public static TraceId generate() {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        long high = rand.nextLong();
        long low = rand.nextLong();
        while (high == 0 && low == 0) {
            low = rand.nextLong();
        }
        return new TraceId(String.format("%016x%016x", high, low));
    }

    public static TraceId fromHex(String hex) {
        Objects.requireNonNull(hex, "hex must not be null");
        String trimmed = hex.trim().toLowerCase();
        if (trimmed.length() != 32) {
            throw new IllegalArgumentException("TraceId hex must be exactly 32 characters, got: " + hex);
        }
        for (int i = 0; i < 32; i++) {
            char c = trimmed.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                throw new IllegalArgumentException("Invalid hex character in TraceId: " + c);
            }
        }
        if (INVALID_HEX.equals(trimmed)) {
            return INVALID;
        }
        return new TraceId(trimmed);
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
        if (!(o instanceof TraceId traceId)) return false;
        return Objects.equals(hex, traceId.hex);
    }

    @Override
    public int hashCode() {
        return hex.hashCode();
    }

    @Override
    public int compareTo(TraceId o) {
        return this.hex.compareTo(o.hex);
    }

    @Override
    public String toString() {
        return hex;
    }
}
