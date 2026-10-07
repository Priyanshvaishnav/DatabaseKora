# KoraDB

> A relational database engine built from scratch in Java 21 — for learning and understanding database internals.

[![Java](https://img.shields.io/badge/Java-21-blue)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Build-Maven-orange)](https://maven.apache.org/)
[![Status](https://img.shields.io/badge/Milestones-All%2016%20Complete-brightgreen)](#milestones)

---

## Goals

KoraDB is not a production database. It is an educational project that implements a relational database engine from first principles — every subsystem is implemented by hand to build deep understanding of how databases work.

The ultimate goal is a system that covers:

- SQL parsing (Lexer → Parser → AST)
- Query execution and planning
- Disk-based storage (pages, records)
- Buffer pool management
- B+ Tree indexes
- Transactions and Write-Ahead Logging
- Crash recovery (ARIES)
- MVCC (Multi-Version Concurrency Control)
- Client-server networking
- Primary/replica replication
- Observability (OpenTelemetry, Prometheus, Grafana)
- Docker and Kubernetes deployment
- AI-assisted query interaction

---

## Architecture Overview

```
SQL text
   ↓
Lexer          (SQL → Tokens)
   ↓
Parser         (Tokens → AST)
   ↓
Planner        (AST → Logical Plan → Physical Plan)   ← Phase 6
   ↓
Executor       (Physical Plan → Results)
   ↓
Buffer Pool    (Pages ↔ Memory cache)                 ← Phase 3
   ↓
Storage Engine (Pages on disk)                        ← Phase 2
```

---

## Milestones

| # | Milestone | Status |
|---|---|---|
| 1 | SQL Engine (in-memory) | ✅ Complete |
| 2 | Storage Engine (disk-backed pages) | ✅ Complete |
| 3 | Buffer Pool | ✅ Complete |
| 4 | B+ Tree Index | ✅ Complete |
| 5 | Query Execution (operator tree) | ✅ Complete |
| 6 | Query Planner and Optimizer | ✅ Complete |
| 7 | Transactions | ✅ Complete |
| 8 | Write-Ahead Logging | ✅ Complete |
| 9 | Crash Recovery (ARIES) | ✅ Complete |
| 10 | MVCC | ✅ Complete |
| 11 | Database Server + CLI | ✅ Complete |
| 12 | Replication | ✅ Complete |
| 13 | Observability | ✅ Complete |
| 14 | Docker + Kubernetes | ✅ Complete |
| 15 | Benchmarks | ✅ Complete |
| 16 | AI Layer | ✅ Complete |

---

## Installation & Quick Start

### 1. One-Line Install (Recommended)

No need to clone or compile. Install pre-built binaries in seconds:

- **macOS / Linux**:
  ```bash
  curl -fsSL https://raw.githubusercontent.com/Priyanshvaishnav/DatabaseKora/main/install.sh | bash
  ```

- **Windows (PowerShell)**:
  ```powershell
  irm https://raw.githubusercontent.com/Priyanshvaishnav/DatabaseKora/main/install.ps1 | iex
  ```

Once installed, use the unified `koradb` command anywhere:
```bash
koradb server --port 8765         # Starts the database server
koradb                            # Launches the interactive CLI & AI prompt
```

---

### 2. Instant Run with Docker

Run KoraDB immediately without installing Java:

```bash
# Start standalone server
docker run -d -p 8765:8765 --name koradb ghcr.io/priyanshvaishnav/koradb:latest

# Connect with the interactive CLI
docker run -it --rm --network host ghcr.io/priyanshvaishnav/koradb:latest cli
```

Or spin up a high-availability cluster with replication, Prometheus, and Grafana:
```bash
docker compose up -d
```

---

### 3. Build from Source (Contributors)

#### Prerequisites
- Java 21+
- Maven 3.9+

```bash
git clone https://github.com/Priyanshvaishnav/DatabaseKora.git
cd DatabaseKora
mvn clean package -DskipTests
./bin/koradb server --port 8765
```

### 4. Connect via JDBC (Microservices & Spring Boot)

Java microservices, Spring Boot applications, and connection pools like HikariCP can connect to KoraDB using standard JDBC:

```properties
# Spring Boot (application.properties)
spring.datasource.url=jdbc:koradb://localhost:8765/orders_db
spring.datasource.driver-class-name=dev.koradb.jdbc.KoraDriver
```

Or using standard Java `DriverManager`:

```java
import java.sql.*;

try (Connection conn = DriverManager.getConnection("jdbc:koradb://localhost:8765/orders_db");
     PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM orders WHERE id = ?;")) {

    pstmt.setInt(1, 101);
    try (ResultSet rs = pstmt.executeQuery()) {
        while (rs.next()) {
            System.out.println(rs.getInt("id") + " - " + rs.getString("customer"));
        }
    }
}
```

---

### Run All Tests

```bash
mvn test
```

### Use the API

```java
KoraDB db = new KoraDB();

db.execute("CREATE DATABASE finance");
db.execute("USE finance");
db.execute("""
    CREATE TABLE accounts (
        id      INT,
        name    VARCHAR,
        balance DOUBLE,
        active  BOOLEAN
    )
""");

db.execute("INSERT INTO accounts VALUES (1, 'Alice', 5000.0, true)");
db.execute("INSERT INTO accounts VALUES (2, 'Bob',   3000.0, false)");

QueryResult result = db.execute("SELECT name, balance FROM accounts WHERE balance > 1000.0");
System.out.print(result.toDisplayString());

// name  | balance
// ------+---------
// Alice | 5000.0
// Bob   | 3000.0

// Explicit ACID Transactions & In-Flight Rollback
db.execute("BEGIN");
db.execute("UPDATE accounts SET balance = 6000.0 WHERE id = 1");
db.execute("INSERT INTO accounts VALUES (3, 'Charlie', 4000.0, true)");
db.execute("ROLLBACK"); // Cleanly restores accounts to pre-transaction state

// Periodic Checkpointing & ARIES Crash Recovery
long checkpointLsn = db.checkpoint(); // Flushes dirty frames & takes ATT/DPT snapshot
dev.koradb.storage.recovery.RecoveryResult result = db.recover(); // 3-phase ARIES recovery

// Multi-Version Concurrency Control (MVCC) & Snapshot Isolation
db.getSession().setDefaultIsolationLevel(dev.koradb.transaction.IsolationLevel.SNAPSHOT);
db.execute("BEGIN");
QueryResult snapshotRead = db.execute("SELECT * FROM accounts WHERE id = 1");
// Concurrent writers on other sessions will not block or affect snapshotRead!
db.execute("COMMIT");

// Vacuum Garbage Collection (reclaims dead tuple versions)
long reclaimedVersions = db.vacuum();
```

### Start the TCP Server & Connect via CLI

```bash
# Start the KoraDB TCP server (default port 8765, Java 21 Virtual Threads)
mvn exec:java -pl kora-server -Dexec.mainClass="dev.koradb.server.ServerMain"

# Connect with the interactive terminal CLI REPL
mvn exec:java -pl kora-cli -Dexec.mainClass="dev.koradb.cli.Main"

# Or execute a one-shot query from terminal
mvn exec:java -pl kora-cli -Dexec.mainClass="dev.koradb.cli.Main" -Dexec.args="-e 'SHOW DATABASES;'"
```

### Connect via Java Client Driver

```java
try (KoraClient client = KoraClient.connect("localhost", 8765)) {
    client.execute("CREATE DATABASE store");
    client.execute("USE store");
    client.execute("CREATE TABLE items (id INT, name VARCHAR, price DOUBLE)");
    client.execute("INSERT INTO items VALUES (1, 'SSD', 120.0)");

    ClientQueryResult res = client.execute("SELECT * FROM items");
    for (List<Object> row : res.rows()) {
        System.out.println(row);
    }
}
```

### Primary / Replica Streaming Replication

```java
// Start Primary Node with ReplicationMaster
KoraDB primaryDb = new KoraDB(Path.of("/var/koradb/primary"));
primaryDb.execute("CREATE DATABASE prod");
primaryDb.execute("USE prod");

DiskCatalog primaryCatalog = (DiskCatalog) primaryDb.getCatalog();
ReplicationMaster master = new ReplicationMaster(
    ReplicationMasterConfig.onPort(8766),
    primaryCatalog.getLogManager(),
    primaryCatalog::getDatabaseTableFiles
);
master.start();

// Start Read-Only Replica connecting to Primary
KoraDB replicaDb = new KoraDB(Path.of("/var/koradb/replica"));
replicaDb.execute("CREATE DATABASE prod");
replicaDb.execute("USE prod");

ReplicationReplica replica = new ReplicationReplica(
    replicaDb,
    ReplicationReplicaConfig.of("replica-1", "localhost", 8766, ReplicationMode.ASYNC)
);
replica.start();

// Primary writes, replica serves read-only queries with MVCC
primaryDb.execute("CREATE TABLE users (id INT, name VARCHAR)");
primaryDb.execute("INSERT INTO users VALUES (1, 'Alice')");

// Consistent lock-free reads on replica
QueryResult res = replica.execute("SELECT * FROM users");

// Promote Replica to Primary during failover
replica.promote();
replica.execute("INSERT INTO users VALUES (2, 'Bob')"); // Now accepts writes!
```

### Observability, Prometheus Exporter & Tracing

```java
// Bind database to ObservabilityEngine
ObservabilityEngine engine = ObservabilityEngine.getInstance();
DiskCatalog catalog = (DiskCatalog) db.getCatalog();

engine.bindLogManager(catalog::getLogManager);
engine.bindTransactionManager(db.getTransactionManager());
engine.bindBufferPools(() -> catalog.getOpenTableFiles().stream()
        .map(tf -> tf.getBufferPool().getStats())
        .toList());

// Starts Prometheus HTTP metrics exporter on port 9100 (/metrics, /health)
engine.startHttpExporter(9100);

// Interactive diagnostic commands
db.execute("SHOW BUFFER POOL STATUS;");
db.execute("SHOW TRANSACTIONS;");
db.execute("SHOW METRICS;");
```

### Docker & Kubernetes Quick Start

```bash
# Run multi-node cluster with Primary, 2 Replicas, Prometheus, and Grafana
docker compose up -d

# Connect to Primary via CLI (Read-Write)
docker run -it --rm --network host koradb:latest cli -h 127.0.0.1 -p 8765

# Connect to Replica 1 via CLI (Read-Only)
docker run -it --rm --network host koradb:latest cli -h 127.0.0.1 -p 8767

# Deploy to Kubernetes via Helm 3
helm install koradb ./helm/koradb -n koradb --create-namespace
```

### Benchmarks (JMH & Micro-Harness)

```bash
# Run the fast built-in micro-harness across all 7 engine subsystems
java -jar benchmarks/target/benchmarks.jar --quick

# Run specific subsystem suite (e.g. buffer pool, transactions, btree, network)
java -jar benchmarks/target/benchmarks.jar --suite buffer
java -jar benchmarks/target/benchmarks.jar --suite tx

# Run with full JMH harness (forked JVMs, statistical warmups & iterations)
java -jar benchmarks/target/benchmarks.jar --jmh
```

### AI Assistant (Natural Language → SQL, Explainer & Advisor)

```bash
# In the interactive CLI:
koradb-cli
kora [(none)]> USE store;

# 1. Translate natural language into verified SQL and execute
kora [store]> \ai find all customers who signed up after 2024
Translating with KoraDB AI (rule-based)...
Generated SQL: SELECT * FROM customers WHERE signup_year > 2024;
Execute query? [Y/n]: y

# 2. Explain query plan in plain English
kora [store]> \explain-ai SELECT * FROM orders WHERE amount > 500;

# 3. Analyze query for missing B+ Tree indexes and generate DDL
kora [store]> \advisor SELECT * FROM orders WHERE customer_id = 42;
```

Or programmatically in Java:
```java
KoraAi ai = KoraAi.builder().database(db).provider("rule-based").build();
TranslationResult res = ai.translate("find users older than 25");
System.out.println(res.generatedSql()); // SELECT * FROM users WHERE age > 25;
```

---

## Module Structure

```
kora-db/
├── kora-core/          ← The database engine (grows each milestone)
│   ├── sql/            ← Lexer, Parser, AST
│   ├── catalog/        ← Schema registry and row storage
│   └── execution/      ← Expression evaluator and statement executor
│
├── kora-server/        ← TCP server and wire protocol    (Phase 11)
├── kora-cli/           ← Interactive REPL                (Phase 11)
├── kora-replication/   ← Primary/replica replication     (Phase 12)
├── kora-observability/ ← OTel, Prometheus, Grafana       (Phase 13)
├── kora-ai/            ← Natural language → SQL          (Phase 16)
├── kora-tests/         ← Cross-module integration tests
├── benchmarks/         ← JMH benchmarks                  (Phase 15)
└── docs/               ← Architecture and design docs
```

---

## Documentation

| Document | Description |
|---|---|
| [Architecture](docs/architecture.md) | Full system architecture and pipeline |
| [SQL Engine](docs/sql-engine.md) | Lexer, Parser, AST design |
| [Storage Engine](docs/storage-engine.md) | Pages, records, disk layout ← Phase 2 |
| [Buffer Pool](docs/buffer-pool.md) | Page caching and eviction ← Phase 3 |
| [B+ Tree](docs/btree.md) | Index structure and operations ← Phase 4 |
| [Query Planner](docs/query-planner.md) | Logical and physical planning ← Phase 6 |
| [Transactions](docs/transactions.md) | BEGIN/COMMIT/ROLLBACK ← Phase 7 |
| [WAL](docs/wal.md) | Write-Ahead Logging ← Phase 8 |
| [Recovery](docs/recovery.md) | Crash recovery algorithm ← Phase 9 |
| [MVCC](docs/mvcc.md) | Multi-version concurrency ← Phase 10 |
| [Server & CLI](docs/server-and-cli.md) | Network TCP server, binary wire protocol & interactive CLI ← Phase 11 |
| [Replication](docs/replication.md) | WAL-based physical replication (Async, Sync, Failover) ← Phase 12 |
| [Observability](docs/observability.md) | Prometheus metrics, HTTP exporter, OTel tracing & Grafana ← Phase 13 |
| [Docker & Kubernetes](docs/docker-and-kubernetes.md) | Multi-stage Docker, Compose cluster, k8s StatefulSets & Helm chart ← Phase 14 |
| [Benchmarks](docs/benchmarks.md) | Performance results ← Phase 15 |
| [AI Layer](docs/ai-layer.md) | Natural language to SQL, query explainer & index optimization advisor ← Phase 16 |

---

## Engineering Philosophy

1. Implement fundamentals by hand — no database libraries
2. Every subsystem must be testable and documented
3. Prefer simple implementations first, then optimize with measurements
4. Clearly document design trade-offs and known limitations
5. Never implement a feature without first explaining the design

---

## Technology Stack

- **Language**: Java 21
- **Build**: Maven
- **Testing**: JUnit 5 + AssertJ + Mockito
- **Logging**: SLF4J + Logback
- **Future**: Docker, Kubernetes, Helm, Prometheus, Grafana, OpenTelemetry

---

## License

MIT
