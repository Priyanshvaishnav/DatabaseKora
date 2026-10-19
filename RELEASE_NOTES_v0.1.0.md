# KoraDB v0.1.0 — High-Performance Relational Engine, JDBC 4.2 Driver & REST Query API

We are thrilled to announce the initial open-source release of **KoraDB (v0.1.0)**! 

KoraDB is an educational, production-grade relational database engine designed and implemented **completely from scratch in Java 21** with **zero third-party database dependencies**. It features a slotted-page disk storage engine, B+ Tree indexing, ARIES crash recovery, ACID transactions, MVCC snapshot isolation, streaming replication, Prometheus observability, and seamless microservice connectivity.

---

## 🌟 Key Highlights & Capabilities

### 1. Universal Microservice & Application Connectivity
* **Standard JDBC 4.2 Driver (`koradb-jdbc.jar`)**: Full compatibility with **Spring Boot**, **HikariCP**, **DBeaver**, and **DataGrip** using standard JDBC URL: `jdbc:koradb://host:port/database`.
* **Built-in HTTP/REST JSON Query API**: High-throughput REST API powered by **Java 21 Virtual Threads** on port `8080` (`POST /v1/query`), enabling **Python**, **Node.js**, **Go**, and **cURL** to query KoraDB out-of-the-box with **zero driver installations**.
* **Interactive Terminal CLI (`koradb-cli`)**: Rich interactive REPL supporting autocompletion, tabular output, and diagnostics.

### 2. Complete Handcrafted Database Kernel
* **SQL Engine**: Custom Lexer, Recursive-Descent Parser, and AST supporting DDL (`CREATE`, `DROP`), DML (`INSERT`, `SELECT`, `UPDATE`, `DELETE`), `JOIN`, `GROUP BY`, `ORDER BY`, `WHERE`, aggregates (`COUNT`, `SUM`, `AVG`, `MIN`, `MAX`), and transactional commands.
* **Storage & Buffer Pool**: 4KB slotted-page disk layout, binary record serialization, and an LRU buffer pool manager with dirty frame tracking.
* **B+ Tree Indexing**: Concurrent disk-backed B+ Trees supporting point lookups, range scans, and node balancing.
* **ACID Transactions & MVCC**: Strict Two-Phase Locking (S2PL), Wait-For-Graph deadlock detection, point-in-time Snapshot Isolation, and background vacuum garbage collection.
* **Durability & Recovery**: Write-Ahead Logging (WAL) with 3-phase ARIES crash recovery (Analysis, Redo, Undo) and periodic checkpointing.
* **Streaming Replication**: Physical WAL streaming with Primary/Replica architecture supporting both Async and Sync modes, with zero-downtime failover and replica promotion.
* **Prometheus Observability**: Native HTTP metrics exporter on port `9100` (`/metrics`, `/health`) and OpenTelemetry-compliant distributed tracing.
* **Built-in AI Assistant**: Natural language to SQL translation, plain-English query execution plans, and B+ Tree index optimization advisor.

---

## 📦 How to Use KoraDB

### 1. Run via Docker
```bash
docker run -d \
  -p 8765:8765 \
  -p 8080:8080 \
  -p 9100:9100 \
  -v koradb_data:/var/lib/koradb \
  ghcr.io/priyanshvaishnav/koradb:latest
```

### 2. Connect via JDBC (Spring Boot & DBeaver)
Download `koradb-jdbc.jar` from the release assets below, then configure `application.properties`:
```properties
spring.datasource.url=jdbc:koradb://localhost:8765/app_db
spring.datasource.driver-class-name=dev.koradb.jdbc.KoraDriver
```

### 3. Query via HTTP REST (Python / Node.js / cURL)
```bash
curl -X POST http://localhost:8080/v1/query \
  -H "Content-Type: application/json" \
  -d '{"database": "app_db", "sql": "SELECT * FROM users"}'
```

```python
import requests

resp = requests.post("http://localhost:8080/v1/query", json={
    "database": "app_db",
    "sql": "SELECT * FROM users"
})
print(resp.json())
```

### 4. Interactive CLI (1-Line Install)
* **Linux / macOS**:
  ```bash
  curl -fsSL https://raw.githubusercontent.com/Priyanshvaishnav/DatabaseKora/main/install.sh | bash
  ```
* **Windows (PowerShell)**:
  ```powershell
  irm https://raw.githubusercontent.com/Priyanshvaishnav/DatabaseKora/main/install.ps1 | iex
  ```

---

## 🛠️ Release Assets
* **`koradb-jdbc.jar`**: Universal JDBC 4.2 driver for microservices, Spring Boot, and DBeaver.
* **`koradb-server.jar`**: Standalone database server runnable with `java -jar koradb-server.jar`.
* **`koradb-cli.jar`**: Standalone terminal client runnable with `java -jar koradb-cli.jar`.
* **`checksums.sha256`**: Cryptographic SHA-256 verification hashes for all downloadable JARs.

---
**Full Changelog**: https://github.com/Priyanshvaishnav/DatabaseKora/commits/v0.1.0
