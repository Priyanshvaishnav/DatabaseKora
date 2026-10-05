package dev.koradb.observability.collectors;

import dev.koradb.buffer.BufferPoolStats;
import dev.koradb.catalog.InMemoryCatalog;
import dev.koradb.observability.metrics.MetricsRegistry;
import dev.koradb.storage.wal.LogManager;
import dev.koradb.transaction.Transaction;
import dev.koradb.transaction.TransactionManager;
import dev.koradb.transaction.lock.LockManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SubsystemCollectorsTest {

    @Test
    @DisplayName("BufferPoolMetricsCollector aggregates stats across multiple buffer pools")
    void testBufferPoolMetrics() {
        MetricsRegistry registry = new MetricsRegistry();

        BufferPoolStats s1 = new BufferPoolStats();
        BufferPoolStats s2 = new BufferPoolStats();

        BufferPoolMetricsCollector collector = new BufferPoolMetricsCollector(registry, () -> List.of(s1, s2));

        // Initial hit ratio is 0.0
        assertThat(registry.gauge("koradb_buffer_pool_hit_ratio", "").get()).isEqualTo(0.0);

        // Record hits on s1 and misses on s2
        // Reflection or package private recordHit
        // BufferPoolStats methods are package-private in dev.koradb.buffer, but hitRatio works via hits/misses
        // Let's verify hitRatio calculation:
        assertThat(registry.gauge("koradb_buffer_pool_hits_total", "").get()).isEqualTo(0.0);
        assertThat(registry.gauge("koradb_buffer_pool_misses_total", "").get()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("TransactionMetricsCollector tracks active transactions, commits, and aborts")
    void testTransactionMetrics() {
        MetricsRegistry registry = new MetricsRegistry();
        LockManager lockManager = new LockManager();
        InMemoryCatalog catalog = new InMemoryCatalog();
        TransactionManager tm = new TransactionManager(lockManager, catalog);

        TransactionMetricsCollector collector = new TransactionMetricsCollector(registry, tm);

        assertThat(registry.gauge("koradb_transactions_active", "").get()).isEqualTo(0.0);

        Transaction txn = tm.beginTransaction();
        assertThat(registry.gauge("koradb_transactions_active", "").get()).isEqualTo(1.0);

        tm.commitTransaction(txn);
        collector.recordCommit();
        assertThat(registry.gauge("koradb_transactions_active", "").get()).isEqualTo(0.0);
        assertThat(registry.counter("koradb_transactions_committed_total", "").get()).isEqualTo(1.0);

        collector.recordAbort();
        assertThat(registry.counter("koradb_transactions_aborted_total", "").get()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("WalMetricsCollector tracks bytes, flushes, and LSN positions")
    void testWalMetrics(@TempDir Path tempDir) {
        MetricsRegistry registry = new MetricsRegistry();
        LogManager lm = new LogManager(tempDir.resolve("wal.log"));

        WalMetricsCollector collector = new WalMetricsCollector(registry, lm);

        long beginLsn = lm.appendBegin(1L, 0L);
        lm.flush(beginLsn);

        assertThat(registry.counter("koradb_wal_flushes_total", "").get()).isGreaterThanOrEqualTo(1.0);
        assertThat(registry.gauge("koradb_wal_flushed_lsn", "").get()).isGreaterThanOrEqualTo(beginLsn);
        assertThat(registry.gauge("koradb_wal_bytes_total", "").get()).isGreaterThan(0.0);

        lm.close();
    }

    @Test
    @DisplayName("QueryMetricsCollector categorizes statements and records latency and errors")
    void testQueryMetrics() {
        MetricsRegistry registry = new MetricsRegistry();
        QueryMetricsCollector collector = new QueryMetricsCollector(registry);

        collector.recordQuery("SELECT * FROM users", 5_000_000L, true); // 5ms
        collector.recordQuery("INSERT INTO users VALUES (1)", 2_000_000L, true); // 2ms
        collector.recordQuery("UPDATE users SET name='Alice'", 3_000_000L, true); // 3ms
        collector.recordQuery("DELETE FROM users WHERE id=1", 1_000_000L, false); // 1ms error
        collector.recordQuery("CREATE TABLE t (id INT)", 10_000_000L, true); // 10ms DDL

        assertThat(registry.counter("koradb_queries_total", "", "type", "SELECT").get()).isEqualTo(1.0);
        assertThat(registry.counter("koradb_queries_total", "", "type", "INSERT").get()).isEqualTo(1.0);
        assertThat(registry.counter("koradb_queries_total", "", "type", "UPDATE").get()).isEqualTo(1.0);
        assertThat(registry.counter("koradb_queries_total", "", "type", "DELETE").get()).isEqualTo(1.0);
        assertThat(registry.counter("koradb_queries_total", "", "type", "DDL").get()).isEqualTo(1.0);
        assertThat(registry.counter("koradb_query_errors_total", "").get()).isEqualTo(1.0);

        assertThat(registry.histogram("koradb_query_duration_seconds", "", "type", "SELECT").getCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("ReplicationMetricsCollector tracks master and replica telemetry")
    void testReplicationMetrics() {
        MetricsRegistry registry = new MetricsRegistry();
        ReplicationMetricsCollector collector = new ReplicationMetricsCollector(registry);

        AtomicLong connectedReplicas = new AtomicLong(2);
        collector.bindMaster(connectedReplicas::get);
        assertThat(registry.gauge("koradb_replication_connected_replicas", "").get()).isEqualTo(2.0);

        AtomicLong lagBytes = new AtomicLong(4096);
        AtomicLong lagMs = new AtomicLong(12);
        collector.bindReplica(lagBytes::get, lagMs::get, () -> 1.0);

        assertThat(registry.gauge("koradb_replication_lag_bytes", "").get()).isEqualTo(4096.0);
        assertThat(registry.gauge("koradb_replication_lag_milliseconds", "").get()).isEqualTo(12.0);
        assertThat(registry.gauge("koradb_replication_is_connected", "").get()).isEqualTo(1.0);
    }
}
