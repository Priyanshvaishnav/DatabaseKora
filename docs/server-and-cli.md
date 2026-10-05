# Database Server, Wire Protocol & CLI — Design Document

## 1. What Problem Does It Solve?

In earlier milestones (1 through 10), KoraDB operated as an **embedded database engine**, accessible only through in-process Java API calls within the same JVM instance.

To function as a true client-server relational database management system (RDBMS), KoraDB requires:
1. **Network Accessibility**: Multiple remote or local processes (applications, microservices, analyst workstations) must connect simultaneously over TCP/IP.
2. **Wire Protocol**: A standardized, compact, and framed binary protocol that cleanly separates application queries, tabular result sets, metadata, heartbeats, and error diagnostics.
3. **Session & Transaction Isolation**: Each connected client must maintain an independent session context, transaction state, and active database selection without interfering with other connections.
4. **Interactive REPL**: Developers and database administrators need an intuitive command-line interface with formatted table outputs, meta-commands, and multi-line SQL editing.

KoraDB delivers these capabilities in **Milestone 11** through `kora-server` (TCP server, wire protocol codec, client driver) and `kora-cli` (interactive REPL and one-shot CLI tool).

---

## 2. Architecture & Concurrency Model

```
 ┌──────────────────────┐          ┌──────────────────────┐
 │    KoraDB CLI        │          │   Java Application   │
 │   (Terminal REPL)    │          │    (KoraClient)      │
 └──────────┬───────────┘          └──────────┬───────────┘
            │                                 │
            │   TCP Connection                │   TCP Connection
            ▼                                 ▼
┌─────────────────────────────────────────────────────────────┐
│                       KoraDB Server                         │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                ServerSocket Listener                  │  │
│  └──────────────────────────┬────────────────────────────┘  │
│                             │                               │
│            Accepts new sockets & spawns                     │
│               Java 21 Virtual Threads                       │
│                             ▼                               │
│  ┌───────────────────────┐     ┌─────────────────────────┐  │
│  │ ClientHandler (Conn 1)│ ... │ ClientHandler (Conn N)  │  │
│  │   • ProtocolCodec     │     │   • ProtocolCodec       │  │
│  │   • Dedicated Session │     │   • Dedicated Session   │  │
│  └──────────┬────────────┘     └────────────┬────────────┘  │
│             │                               │               │
│             └───────────────┬───────────────┘               │
│                             ▼                               │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                  KoraDB Core Engine                   │  │
│  │  • SQL Parser & Planner                               │  │
│  │  • Multi-Version Concurrency Control (MVCC)           │  │
│  │  • Disk Catalog, Slotted Pages, Buffer Pool, WAL      │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### High-Concurrency Virtual Threads
Rather than pinning an OS-level platform thread to every client connection (which limits scalability to thousands of threads) or using complex reactive callback pipelines, `kora-server` leverages **Java 21 Virtual Threads** (`Executors.newVirtualThreadPerTaskExecutor()`):
- Each incoming TCP connection runs on a lightweight virtual thread.
- Blocking network reads and synchronous database calls yield automatically without consuming OS thread pool capacity.
- Enables hundreds or thousands of concurrent connections with minimal memory overhead.

---

## 3. Wire Protocol Specification

KoraDB uses a framed binary protocol designed for simplicity, performance, and low overhead.

### 3.1 Packet Framing
Every message transmitted across the network begins with a **6-byte header**:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Magic (0x4B) | MsgType (1B)  |         Payload Length        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| Payload Length (cont. int32)  |         Payload bytes...      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+                               |
|                              ...                              |
+---------------------------------------------------------------+
```

- **Magic Byte (`0x4B`, 'K')**: Confirms protocol alignment and guards against accidental connections from non-KoraDB clients.
- **Message Type (`1 byte`)**: Identifies the message payload type (see table below).
- **Payload Length (`4 bytes, int32 big-endian`)**: Exact byte count of the payload, capped at 16 MB (`MAX_PAYLOAD_SIZE = 16 * 1024 * 1024`) to protect against out-of-memory denial of service.

### 3.2 Message Types

| Code | Type | Direction | Description |
|---|---|---|---|
| `0x01` | `CONNECT` | Client → Server | Protocol version, client identifier, and initial database |
| `0x02` | `CONNECT_OK` | Server → Client | Server version string, connection ID, and welcome greeting |
| `0x03` | `QUERY` | Client → Server | SQL statement text (UTF-8) |
| `0x04` | `QUERY_RESULT` | Server → Client | Execution result: affected row count, columns, and typed row values |
| `0x05` | `ERROR` | Server → Client | Error code (`int32`) and human-readable diagnostic message |
| `0x06` | `PING` | Bidirectional | Connection heartbeat probe |
| `0x07` | `PONG` | Bidirectional | Connection heartbeat acknowledgment |
| `0x08` | `DISCONNECT` | Client → Server | Graceful termination signal before socket close |

### 3.3 Value Serialization in Result Sets
Column values in `QUERY_RESULT` payloads are serialized with a 1-byte type tag:
- `0x00`: `NULL`
- `0x01`: `INT` (32-bit signed integer)
- `0x02`: `BIGINT` (64-bit signed integer)
- `0x03`: `BOOLEAN` (1 byte: `0` or `1`)
- `0x04`: `VARCHAR` (length-prefixed UTF-8 string)
- `0x05`: `DOUBLE` (64-bit IEEE 754 floating point)
- `0x06`: `TIMESTAMP` (length-prefixed ISO-8601 string)

---

## 4. Server Engine (`kora-server`)

### Connection Lifecycle & Handshake
1. **Accept**: The acceptor thread accepts the client socket and configures `TCP_NODELAY`.
2. **Handshake**: The client immediately sends `CONNECT`. If the client requests an initial database, the server switches to that database on the client's session. The server responds with `CONNECT_OK`.
3. **Query Loop**: The client issues `QUERY` packets containing SQL statements. The server evaluates queries on the connection's dedicated `Session`.
4. **Auto-Rollback on Disconnect**: If a client socket drops or disconnects with an uncommitted transaction in progress, `ClientHandler.close()` automatically issues a `ROLLBACK` to release all locks and restore MVCC version visibility.

### Built-in Catalog Meta-Queries
`kora-server` natively handles metadata discovery queries:
- `SHOW DATABASES;`: Returns a tabular list of all databases in the catalog.
- `SHOW TABLES;`: Returns all tables in the active database.
- `DESCRIBE <table_name>;`: Returns column names and data types for the specified table.

---

## 5. Client Driver (`KoraClient`)

The `dev.koradb.client.KoraClient` class provides a clean, zero-dependency Java client API:

```java
try (KoraClient client = KoraClient.connect("127.0.0.1", 8765, "finance")) {
    // 1. DDL & DML
    client.execute("CREATE TABLE accounts (id INT, name VARCHAR, balance DOUBLE);");
    client.execute("INSERT INTO accounts VALUES (1, 'Alice', 5000.0);");

    // 2. Querying with ClientQueryResult
    ClientQueryResult res = client.execute("SELECT id, name, balance FROM accounts;");
    for (List<Object> row : res.rows()) {
        int id = (int) row.get(0);
        String name = (String) row.get(1);
        double balance = (double) row.get(2);
        System.out.printf("%d: %s ($%.2f)%n", id, name, balance);
    }

    // 3. Transactions
    client.execute("BEGIN;");
    client.execute("UPDATE accounts SET balance = 5500.0 WHERE id = 1;");
    client.execute("COMMIT;");
}
```

---

## 6. Interactive Command-Line Interface (`kora-cli`)

### Launching the CLI
```bash
# Interactive REPL mode
java -jar kora-cli.jar --host 127.0.0.1 --port 8765 --database finance

# One-shot query execution
java -jar kora-cli.jar --port 8765 -e "SELECT * FROM accounts;"
```

### CLI Command Options
- `-h, --host <HOST>`: Server hostname or IP address (default: `127.0.0.1`).
- `-p, --port <PORT>`: Server TCP port (default: `8765`).
- `-d, --database <DB>`: Database to select immediately upon connection.
- `-e, --execute <SQL>`: Executes the specified query, prints the result, and exits.
- `-v, --version`: Prints version information.
- `--help`: Displays the help menu.

### Interactive REPL Features
1. **Dynamic Prompt**: Reflects active database (`kora [(none)]> ` or `kora [finance]> `).
2. **Multi-line Query Accumulation**: Prompts with `   -> ` on line continuations until a terminating semicolon `;` is encountered.
3. **Tabular ASCII Output**: Automatically computes optimal column widths, right-aligns numbers, left-aligns strings, and displays execution latency:
```
+----+-------+---------+
| id | name  | balance |
+----+-------+---------+
|  1 | Alice |  5500.0 |
+----+-------+---------+
1 row in set (3.20 ms)
```

4. **Meta-Commands**:
- `\l`, `\databases`: List all databases.
- `\dt`, `\tables`: List tables in the current database.
- `\d <table_name>`: Describe table column names and types.
- `\c <database>`: Connect to / switch database.
- `\c`: Clear current query input buffer.
- `\s`, `\status`: Display connection status, session ID, and active database.
- `\h`, `\help`: Print help manual.
- `\q`, `\quit`, `exit`: Disconnect and exit.

---

## 7. Multi-Client Concurrency & Network MVCC

Through the combination of `kora-server` and `kora-core`:
1. **Repeatable Read Snapshot Isolation over TCP**:
   - Connection A starts a transaction under `SNAPSHOT` isolation (`BEGIN;`).
   - Connection B connects, executes an `UPDATE`, and commits.
   - Connection A executes queries: observes consistent snapshot without seeing B's changes until A commits!
2. **Non-Blocking Reads**:
   - Long-running queries on Connection A never block concurrent writes on Connection B.
3. **First-Committer-Wins Write-Conflict Detection**:
   - Concurrent updates to the same row by independent TCP connections trigger `WriteConflictException` and are returned to the client as error code `1004 (ERR_CONFLICT)`.
