package dev.koradb.benchmarks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class BenchmarkSmokeTest {

    @Test
    @DisplayName("Should run all subsystem microbenchmarks in quick mode without exceptions")
    void testAllSubsystemBenchmarksQuick() throws Exception {
        List<BenchmarkResult> results = BenchmarkRunner.runBuiltin("all", true, false);

        assertThat(results).isNotEmpty();
        assertThat(results.size()).isGreaterThanOrEqualTo(25);

        for (BenchmarkResult res : results) {
            assertThat(res.operations()).isGreaterThan(0L);
            assertThat(res.opsPerSec()).isGreaterThan(0.0);
            assertThat(res.avgLatencyUs()).isGreaterThan(0.0);
            assertThat(res.toDisplayRow()).isNotBlank();
        }
    }

    @Test
    @DisplayName("CLI help should execute without errors")
    void testCliHelp() {
        assertDoesNotThrow(() -> BenchmarkRunner.main(new String[]{"--help"}));
    }
}
