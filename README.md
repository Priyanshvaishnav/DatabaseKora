# KoraDB

> A relational database engine built from scratch in Java 21 — for learning and understanding database internals.

[![Java](https://img.shields.io/badge/Java-21-blue)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Build-Maven-orange)](https://maven.apache.org/)
[![Status](https://img.shields.io/badge/Milestone-9%20Crash%20Recovery-green)](#milestones)

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
| 10 | MVCC | 🔜 Next |
| 11 | Database Server + CLI | ⬜ |
| 12 | Replication | ⬜ |
| 13 | Observability | ⬜ |
| 14 | Docker + Kubernetes | ⬜ |
| 15 | Benchmarks | ⬜ |
| 16 | AI Layer | ⬜ |

---

## Quick Start

### Prerequisites

- Java 21
- Maven 3.9+

### Build

```bash
cd kora-db
mvn clean install
```

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
| [Replication](docs/replication.md) | WAL-based replication ← Phase 12 |
| [Observability](docs/observability.md) | Metrics and tracing ← Phase 13 |
| [Benchmarks](docs/benchmarks.md) | Performance results ← Phase 15 |

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
