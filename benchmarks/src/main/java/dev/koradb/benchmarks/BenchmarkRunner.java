package dev.koradb.benchmarks;

import dev.koradb.benchmarks.btree.BTreeBenchmark;
import dev.koradb.benchmarks.buffer.BufferPoolBenchmark;
import dev.koradb.benchmarks.network.ServerNetworkBenchmark;
import dev.koradb.benchmarks.parser.SqlParserBenchmark;
import dev.koradb.benchmarks.query.QueryExecutionBenchmark;
import dev.koradb.benchmarks.replication.ReplicationBenchmark;
import dev.koradb.benchmarks.transaction.TransactionMvccBenchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Universal benchmark executor and diagnostic runner for KoraDB.
 * Supports both standard JMH runner execution and built-in interactive CLI runs.
 */
public final class BenchmarkRunner {

    public static void main(String[] args) throws Exception {
        boolean useJmh = false;
        boolean quick = false;
        boolean jsonOutput = false;
        String suiteFilter = "all";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--jmh" -> useJmh = true;
                case "--quick" -> quick = true;
                case "--json" -> jsonOutput = true;
                case "--suite" -> {
                    if (++i < args.length) suiteFilter = args[i].toLowerCase();
                }
                case "--help", "-h" -> {
                    printHelp();
                    return;
                }
            }
        }

        if (useJmh) {
            runJmh(suiteFilter, quick);
        } else {
            runBuiltin(suiteFilter, quick, jsonOutput);
        }
    }

    public static void runJmh(String suiteFilter, boolean quick) throws Exception {
        String regex = switch (suiteFilter) {
            case "parser" -> ".*SqlParserBenchmark.*";
            case "btree" -> ".*BTreeBenchmark.*";
            case "buffer" -> ".*BufferPoolBenchmark.*";
            case "query" -> ".*QueryExecutionBenchmark.*";
            case "transaction", "mvcc", "tx" -> ".*TransactionMvccBenchmark.*";
            case "network", "server" -> ".*ServerNetworkBenchmark.*";
            case "replication" -> ".*ReplicationBenchmark.*";
            default -> "dev.koradb.benchmarks.*";
        };

        int warmups = quick ? 1 : 2;
        int iterations = quick ? 1 : 3;

        Options opt = new OptionsBuilder()
                .include(regex)
                .forks(1)
                .warmupIterations(warmups)
                .measurementIterations(iterations)
                .build();

        new Runner(opt).run();
    }

    public static List<BenchmarkResult> runBuiltin(String suiteFilter, boolean quick, boolean jsonOutput) throws Exception {
        List<BenchmarkResult> results = new ArrayList<>();
        long targetDurationMs = quick ? 60L : 1200L;

        System.out.println("==========================================================================================");
        System.out.println("  KoraDB Subsystem Benchmarks (Mode: Built-in Micro-Harness, Duration: " + targetDurationMs + "ms/bench)");
        System.out.println("==========================================================================================");

        // 1. Parser
        if (suiteFilter.equals("all") || suiteFilter.contains("parser")) {
            SqlParserBenchmark p = new SqlParserBenchmark();
            results.add(measure("SqlParser", "tokenizeSimple", targetDurationMs, p::tokenizeSimpleSelect));
            results.add(measure("SqlParser", "tokenizeComplex", targetDurationMs, p::tokenizeComplexSelect));
            results.add(measure("SqlParser", "parseSimpleSelect", targetDurationMs, p::parseSimpleSelect));
            results.add(measure("SqlParser", "parseComplexSelect", targetDurationMs, p::parseComplexSelect));
            results.add(measure("SqlParser", "parseInsert", targetDurationMs, p::parseInsert));
            results.add(measure("SqlParser", "parseUpdate", targetDurationMs, p::parseUpdate));
            results.add(measure("SqlParser", "parseCreateTable", targetDurationMs, p::parseCreateTable));
        }

        // 2. B+ Tree
        if (suiteFilter.equals("all") || suiteFilter.contains("btree")) {
            BTreeBenchmark b = new BTreeBenchmark();
            b.setup();
            try {
                results.add(measure("BPlusTree", "pointLookupRandom", targetDurationMs, b::pointLookupRandom));
                results.add(measure("BPlusTree", "rangeScanSmall (20)", targetDurationMs, b::rangeScanSmall));
                results.add(measure("BPlusTree", "rangeScanMedium (200)", targetDurationMs, b::rangeScanMedium));
                results.add(measure("BPlusTree", "insertNewKey", targetDurationMs, b::insertNewKey));
            } finally {
                b.tearDown();
            }
        }

        // 3. Buffer Pool
        if (suiteFilter.equals("all") || suiteFilter.contains("buffer")) {
            BufferPoolBenchmark bp = new BufferPoolBenchmark();
            bp.setup();
            try {
                results.add(measure("BufferPool", "cacheHitRead", targetDurationMs, bp::cacheHitRead));
                results.add(measure("BufferPool", "cacheHitDirtyWrite", targetDurationMs, bp::cacheHitDirtyWrite));
                results.add(measure("BufferPool", "lruEvictionCycle", targetDurationMs, bp::lruEvictionCycle));
            } finally {
                bp.tearDown();
            }
        }

        // 4. Query Execution
        if (suiteFilter.equals("all") || suiteFilter.contains("query")) {
            QueryExecutionBenchmark q = new QueryExecutionBenchmark();
            q.setup();
            try {
                results.add(measure("QueryExecution", "pointQueryIndexed", targetDurationMs, q::pointQueryIndexed));
                results.add(measure("QueryExecution", "rangeScanFilter", targetDurationMs, q::rangeScanFilter));
                results.add(measure("QueryExecution", "aggregationGroupBy", targetDurationMs, q::aggregationGroupBy));
                results.add(measure("QueryExecution", "joinQuery", targetDurationMs, q::joinQuery));
                results.add(measure("QueryExecution", "sortAndLimit", targetDurationMs, q::sortAndLimit));
            } finally {
                q.tearDown();
            }
        }

        // 5. Transaction / MVCC / WAL
        if (suiteFilter.equals("all") || suiteFilter.contains("tx") || suiteFilter.contains("transaction") || suiteFilter.contains("mvcc")) {
            TransactionMvccBenchmark t = new TransactionMvccBenchmark();
            t.setup();
            try {
                results.add(measure("TransactionMVCC", "snapshotRead", targetDurationMs, t::snapshotRead));
                results.add(measure("TransactionMVCC", "shortTransaction", targetDurationMs, t::shortTransaction));
                results.add(measure("TransactionMVCC", "walAppendRecord", targetDurationMs, t::walAppendRecord));
                results.add(measure("TransactionMVCC", "logRecordEncode", targetDurationMs, t::logRecordEncode));
                results.add(measure("TransactionMVCC", "logRecordDecode", targetDurationMs, t::logRecordDecode));
            } finally {
                t.tearDown();
            }
        }

        // 6. Network & Wire Protocol
        if (suiteFilter.equals("all") || suiteFilter.contains("network") || suiteFilter.contains("server")) {
            ServerNetworkBenchmark n = new ServerNetworkBenchmark();
            n.setup();
            try {
                results.add(measure("NetworkProtocol", "encodeQueryMessage", targetDurationMs, n::encodeQueryMessage));
                results.add(measure("NetworkProtocol", "decodeQueryMessage", targetDurationMs, n::decodeQueryMessage));
                results.add(measure("NetworkProtocol", "decodeQueryResult", targetDurationMs, n::decodeQueryResult));
                results.add(measure("NetworkProtocol", "clientPingRoundTrip", targetDurationMs, n::clientPingRoundTrip));
                results.add(measure("NetworkProtocol", "clientQueryRoundTrip", targetDurationMs, n::clientQueryRoundTrip));
            } finally {
                n.tearDown();
            }
        }

        // 7. Replication
        if (suiteFilter.equals("all") || suiteFilter.contains("replication")) {
            ReplicationBenchmark r = new ReplicationBenchmark();
            r.setup();
            try {
                results.add(measure("Replication", "encodeHeartbeat", targetDurationMs, r::encodeHeartbeat));
                results.add(measure("Replication", "decodeHeartbeat", targetDurationMs, r::decodeHeartbeat));
                results.add(measure("Replication", "encodeWalAck", targetDurationMs, r::encodeWalAck));
                results.add(measure("Replication", "decodeWalAck", targetDurationMs, r::decodeWalAck));
                results.add(measure("Replication", "encodeWalDataBatch", targetDurationMs, r::encodeWalDataBatch));
                results.add(measure("Replication", "decodeWalDataBatch", targetDurationMs, r::decodeWalDataBatch));
            } finally {
                r.tearDown();
            }
        }

        printReport(results, jsonOutput);
        return results;
    }

    private static BenchmarkResult measure(String suite, String name, long targetDurationMs, BenchmarkAction action) throws Exception {
        // Warmup phase (15ms for quick mode, 100ms for full mode)
        long warmupMs = targetDurationMs < 200L ? 15L : 100L;
        long warmupEnd = System.currentTimeMillis() + warmupMs;
        while (System.currentTimeMillis() < warmupEnd) {
            action.run();
        }

        // Measurement phase
        long startNanos = System.nanoTime();
        long targetNanos = targetDurationMs * 1_000_000L;
        long count = 0;

        List<Double> sampleLatencies = new ArrayList<>();
        long minNanos = Long.MAX_VALUE;
        long maxNanos = 0;

        while ((System.nanoTime() - startNanos) < targetNanos) {
            long t0 = System.nanoTime();
            action.run();
            long elapsed = System.nanoTime() - t0;
            count++;

            if (elapsed < minNanos) minNanos = elapsed;
            if (elapsed > maxNanos) maxNanos = elapsed;

            if (count % 20 == 0 && sampleLatencies.size() < 1000) {
                sampleLatencies.add(elapsed / 1000.0);
            }
        }

        long totalNanos = System.nanoTime() - startNanos;
        double durationSeconds = totalNanos / 1_000_000_000.0;
        double opsPerSec = count / durationSeconds;
        double avgLatencyUs = (totalNanos / 1000.0) / count;

        double p95LatencyUs = avgLatencyUs;
        if (!sampleLatencies.isEmpty()) {
            Double[] latencies = sampleLatencies.toArray(new Double[0]);
            Arrays.sort(latencies);
            int idx = (int) (latencies.length * 0.95);
            p95LatencyUs = latencies[Math.min(idx, latencies.length - 1)];
        }

        BenchmarkResult result = new BenchmarkResult(
                suite,
                name,
                count,
                durationSeconds,
                opsPerSec,
                avgLatencyUs,
                p95LatencyUs,
                minNanos / 1000.0,
                maxNanos / 1000.0
        );

        System.out.println(result.toDisplayRow());
        return result;
    }

    private static void printReport(List<BenchmarkResult> results, boolean json) {
        System.out.println("==========================================================================================");
        if (json) {
            System.out.println("[");
            for (int i = 0; i < results.size(); i++) {
                BenchmarkResult r = results.get(i);
                System.out.printf("  {\"suite\":\"%s\",\"benchmark\":\"%s\",\"opsPerSec\":%.2f,\"avgLatencyUs\":%.2f,\"p95LatencyUs\":%.2f}%s%n",
                        r.suite(), r.benchmarkName(), r.opsPerSec(), r.avgLatencyUs(), r.p95LatencyUs(),
                        (i < results.size() - 1 ? "," : ""));
            }
            System.out.println("]");
        } else {
            System.out.printf("Completed %d benchmark operations across %d suites successfully.%n",
                    results.stream().mapToLong(BenchmarkResult::operations).sum(), results.size());
        }
        System.out.println("==========================================================================================");
    }

    private static void printHelp() {
        System.out.println("""
                Usage: koradb-benchmarks [OPTIONS]
                
                Runs KoraDB performance microbenchmarks across all database engine subsystems.
                
                Options:
                  --suite <NAME>    Filter benchmark suite (all, parser, btree, buffer, query, tx, network, replication)
                  --jmh             Execute using full JMH Harness with forks and statistical iterations
                  --quick           Run faster iterations for quick smoke testing
                  --json            Output results as formatted JSON
                  -h, --help        Show this help message and exit
                """);
    }

    @FunctionalInterface
    public interface BenchmarkAction {
        void run() throws Exception;
    }
}
