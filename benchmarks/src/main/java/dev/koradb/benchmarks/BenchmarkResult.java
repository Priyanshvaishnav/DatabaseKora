package dev.koradb.benchmarks;

/**
 * Encapsulates the quantitative performance outcome of a benchmark execution.
 */
public record BenchmarkResult(
        String suite,
        String benchmarkName,
        long operations,
        double durationSeconds,
        double opsPerSec,
        double avgLatencyUs,
        double p95LatencyUs,
        double minLatencyUs,
        double maxLatencyUs
) {
    public String toDisplayRow() {
        return String.format("| %-22s | %-24s | %,12.1f ops/s | %8.2f µs | %8.2f µs |",
                suite, benchmarkName, opsPerSec, avgLatencyUs, p95LatencyUs);
    }
}
