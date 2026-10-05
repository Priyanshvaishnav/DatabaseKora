# Observability, Prometheus Metrics & OpenTelemetry Tracing — Design Document

## 1. What Problem Does It Solve?

In distributed and high-performance database systems, understanding internal engine behavior is critical for performance tuning, capacity planning, and incident diagnosis. Database operators, Site Reliability Engineers (SREs), and developers need deep visibility into:

1. **Engine Metrics & Resource Utilization**: Page cache hit ratios in the buffer pool, active transaction counts, commit/abort ratios, lock contention, write-ahead log fsync latency, query throughput (QPS), and replication lag.
2. **Standardized Scraping & Alerting**: Out-of-the-box compatibility with Prometheus and Grafana for automated scraping, dashboard visualization, and proactive alert triggering.
3. **End-to-End Query Profiling & Distributed Tracing**: Tracing individual query journeys through lexical analysis, parsing, query planning, operator tree execution, and physical disk I/O, compatible with OpenTelemetry and Jaeger.
4. **Interactive Inspection**: Real-time diagnostic SQL commands (`SHOW METRICS`, `SHOW BUFFER POOL STATUS`, `SHOW TRANSACTIONS`) executable directly from the terminal CLI or application client.

KoraDB delivers these capabilities in **Milestone 13** through the `kora-observability` module, implemented completely **from scratch using the Java 21 standard library** with zero external metric or networking libraries.

---

## 2. Architecture & Components

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              KoraDB Instance                                │
│                                                                             │
│  ┌───────────────────────┐            ┌──────────────────────────────────┐  │
│  │   Core Database       │            │       ObservabilityEngine        │  │
│  │   • Query Executor    ├───────────►│  • Tracer (Span hierarchy)       │  │
│  │   • Buffer Pool       │   records  │  • MetricsRegistry               │  │
│  │   • LogManager (WAL)  │   events   │    - Counters                    │  │
│  │   • TransactionManager│            │    - Gauges                      │  │
│  │   • ReplicationMaster │            │    - Histograms                  │  │
│  └───────────────────────┘            └───────────────┬──────────────────┘  │
│                                                       │                     │
│                                        ┌──────────────┴──────────────┐      │
│                                        ▼                             ▼      │
│                        ┌───────────────────────────────┐ ┌───────────────┐  │
│                        │   Prometheus HTTP Exporter    │ │ SHOW METRICS  │  │
│                        │   (com.sun.net.httpserver)    │ │  SQL Command  │  │
│                        │   Port 9100 (/metrics)        │ │  (CLI/Driver) │  │
│                        └───────────────┬───────────────┘ └───────────────┘  │
└────────────────────────────────────────┼────────────────────────────────────┘
                                         │ scrape
                                         ▼
                         ┌───────────────────────────────┐
                         │       Prometheus Server       │
                         └───────────────┬───────────────┘
                                         │ query
                                         ▼
                         ┌───────────────────────────────┐
                         │       Grafana Dashboard       │
                         └───────────────────────────────┘
```

### Core Components

- **`ObservabilityEngine`**: Central coordinator and singleton access point wiring collectors, registries, tracers, and the HTTP server.
- **`MetricsRegistry`**: High-concurrency thread-safe catalog maintaining `Counter`, `Gauge`, and `Histogram` metrics.
- **`PrometheusFormatter`**: Serializes the in-memory metric catalog into the standard Prometheus Exposition Text Format (0.0.4).
- **`MetricsHttpServer`**: Ultra-lightweight HTTP server running on Java 21 Virtual Threads (`com.sun.net.httpserver.HttpServer`) serving `/metrics`, `/health`, and `/status`.
- **`Tracer` & `Span`**: OpenTelemetry-compatible tracing abstractions supporting nested spans, attributes, timed events, error recording, and W3C `traceparent` context propagation.

---

## 3. Metrics Engine

### Supported Metric Types

| Type | Class | Description | Example |
|---|---|---|---|
| **Counter** | `Counter` | Monotonically increasing cumulative double/long value. | `koradb_queries_total{type="SELECT"}` |
| **Gauge** | `Gauge` | Instantaneous value that fluctuates up and down; supports settable and supplier-backed evaluation. | `koradb_buffer_pool_hit_ratio` |
| **Histogram** | `Histogram` | Samples values into configurable upper-bound buckets; computes count, sum, and quantile percentiles (p50, p95, p99). | `koradb_query_duration_seconds` |

### Prometheus Text Format Example

```text
# HELP koradb_queries_total Total SQL queries executed
# TYPE koradb_queries_total counter
koradb_queries_total{type="SELECT"} 1240
koradb_queries_total{type="INSERT"} 450
koradb_queries_total{type="DDL"} 12

# HELP koradb_buffer_pool_hit_ratio Buffer pool cache hit ratio (0.0 to 1.0)
# TYPE koradb_buffer_pool_hit_ratio gauge
koradb_buffer_pool_hit_ratio 0.985

# HELP koradb_query_duration_seconds SQL query execution duration in seconds
# TYPE koradb_query_duration_seconds histogram
koradb_query_duration_seconds_bucket{le="0.001",type="SELECT"} 820
koradb_query_duration_seconds_bucket{le="0.005",type="SELECT"} 1190
koradb_query_duration_seconds_bucket{le="0.01",type="SELECT"} 1235
koradb_query_duration_seconds_bucket{le="+Inf",type="SELECT"} 1240
koradb_query_duration_seconds_sum{type="SELECT"} 1.482
koradb_query_duration_seconds_count{type="SELECT"} 1240
```

---

## 4. Subsystem Telemetry Reference

### Buffer Pool Telemetry
- `koradb_buffer_pool_hits_total`: Total page cache hits across open tables.
- `koradb_buffer_pool_misses_total`: Total page cache misses requiring disk reads.
- `koradb_buffer_pool_hit_ratio`: Hit ratio in `[0.0, 1.0]`. Optimal OLTP workloads maintain > 0.95.
- `koradb_buffer_pool_evictions_total`: Total frames evicted by LRU algorithm.
- `koradb_buffer_pool_dirty_evictions_total`: Evicted dirty frames that required a disk write.

### Transaction Telemetry
- `koradb_transactions_active`: Count of currently active in-flight transactions.
- `koradb_transactions_committed_total`: Total successfully committed transactions.
- `koradb_transactions_aborted_total`: Total rolled back transactions.

### Write-Ahead Log (WAL) Telemetry
- `koradb_wal_bytes_total`: Total bytes written to the WAL on disk (excluding header).
- `koradb_wal_flushes_total`: Total `fsync()` operations executed.
- `koradb_wal_flushed_lsn`: Most recent LSN position guaranteed on disk.
- `koradb_wal_flush_duration_seconds`: Histogram of disk sync durations.

### Query Engine Telemetry
- `koradb_queries_total{type="..."}`: Queries executed by category (`SELECT`, `INSERT`, `UPDATE`, `DELETE`, `DDL`, `OTHER`).
- `koradb_query_duration_seconds{type="..."}`: Execution latency histogram.
- `koradb_query_errors_total`: Total queries that threw runtime exceptions.

### Replication Telemetry
- `koradb_replication_connected_replicas`: Connected replicas on Primary node.
- `koradb_replication_lag_bytes`: Unapplied bytes behind Primary WAL head.
- `koradb_replication_lag_milliseconds`: Estimated replication latency in milliseconds.
- `koradb_replication_is_connected`: Replica connection status (1.0 = connected, 0.0 = disconnected).

---

## 5. Distributed Tracing & Query Profiling

KoraDB implements OpenTelemetry-compatible tracing with W3C `traceparent` context propagation (`version-trace_id-parent_id-trace_flags`):

### Query Span Hierarchy

```
db.query (Root Span)
│   Attributes: db.statement="SELECT * FROM accounts WHERE id = 1", client.address="127.0.0.1"
│
├── db.parse (Child Span)
│       Lexer tokenization and AST parsing
│
├── db.plan (Child Span)
│       Logical & physical plan optimization
│
└── db.execute (Child Span)
    │   Physical operator tree evaluation
    │
    ├── storage.page_read (Child Span)
    │       Buffer pool frame access or disk read
    │
    └── wal.append (Child Span)
            WAL record encoding and buffer allocation
```

### Context Propagation Example
```java
// Parsing incoming W3C header from HTTP/gRPC/wire request
SpanContext parentContext = SpanContext.fromTraceParent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

Span querySpan = tracer.spanBuilder("db.query")
        .setParent(parentContext)
        .setAttribute("db.statement", "SELECT * FROM users")
        .start();

try (Tracer.Scope scope = tracer.withSpan(querySpan)) {
    // Child spans automatically inherit traceId and set querySpan as parentSpanId!
    Span parseSpan = tracer.startSpan("db.parse");
    parseSpan.end();
} finally {
    querySpan.end();
}
```

---

## 6. Interactive Observability SQL Commands

KoraDB supports built-in diagnostic statements executable from CLI REPL and client drivers:

### `SHOW BUFFER POOL STATUS;`
```
+-----------------+---------+
| metric          | value   |
+-----------------+---------+
| hits            | 4520    |
| misses          | 80      |
| hit_ratio       | 98.26%  |
| evictions       | 12      |
| dirty_evictions | 3       |
+-----------------+---------+
```

### `SHOW TRANSACTIONS;`
```
+--------+--------+------------------+-------------------+
| txn_id | state  | isolation_level  | start_time_millis |
+--------+--------+------------------+-------------------+
| 104    | ACTIVE | SNAPSHOT         | 1728145290000     |
+--------+--------+------------------+-------------------+
```

### `SHOW METRICS;`
```
+-----------------------------------+-----------+-----------------------------------+
| name                              | type      | value                             |
+-----------------------------------+-----------+-----------------------------------+
| koradb_buffer_pool_hit_ratio      | GAUGE     | 0.9826                            |
| koradb_queries_total{type="SELECT"}| COUNTER   | 120.0                             |
| koradb_wal_bytes_total            | GAUGE     | 18432.0                           |
| koradb_query_duration_seconds     | HISTOGRAM | count=120, sum=0.1420, p95=0.0042 |
+-----------------------------------+-----------+-----------------------------------+
```

---

## 7. Grafana & Prometheus Setup

### Prometheus Scrape Configuration (`prometheus.yml`)
```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: "koradb"
    metrics_path: "/metrics"
    static_configs:
      - targets: ["localhost:9100"]
```

### Grafana Dashboard Import
1. Navigate to Grafana UI -> **Dashboards** -> **New** -> **Import**.
2. Upload `kora-observability/dashboards/koradb-overview.json`.
3. Select your Prometheus data source and click **Import**.

### Prometheus Alerting Rules (`koradb-alerts.yml`)
Included rules alert when:
- Buffer pool hit ratio drops below 80% for > 2m.
- 95th percentile query latency exceeds 500ms for > 2m.
- High transaction abort rate occurs (> 10 aborts/sec).
- Replication latency lag exceeds 5000ms.
- Database instance goes down (`up == 0`).

---

## 8. Usage Example

```java
// 1. Initialize Database & Observability
KoraDB db = new KoraDB(Path.of("/var/koradb/data"));
ObservabilityEngine engine = ObservabilityEngine.getInstance();

// 2. Bind subsystem collectors
DiskCatalog catalog = (DiskCatalog) db.getCatalog();
engine.bindLogManager(catalog::getLogManager);
engine.bindTransactionManager(db.getTransactionManager());
engine.bindBufferPools(() -> catalog.getOpenTableFiles().stream()
        .map(tf -> tf.getBufferPool().getStats())
        .toList());

// 3. Start Prometheus HTTP Exporter on port 9100
engine.startHttpExporter(9100);

// 4. Queries automatically update metrics and can be traced
long start = System.nanoTime();
try {
    db.execute("SELECT * FROM orders WHERE total > 100.0");
    engine.recordQuery("SELECT * FROM orders WHERE total > 100.0", System.nanoTime() - start, true);
} catch (Exception e) {
    engine.recordQuery("SELECT * FROM orders WHERE total > 100.0", System.nanoTime() - start, false);
}
```
