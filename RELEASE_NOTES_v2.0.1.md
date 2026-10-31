# KoraDB v2.0.1: Correctness, Fuller SQL, Authentication and Safer Failover

KoraDB v2.0.1 fixes the critical bugs found by end-to-end testing of the previous release, including lost rows, duplicated rows and databases that could not be reopened. It also adds much more SQL, password authentication and a safer replica failover.

Changes since `v1.0.0` are listed below. Some of them change existing behavior, so read [Behavior changes](#behavior-changes) before upgrading. Use this release instead of the `V2.0.0` tag, which has no `VARCHAR(n)` support and still reports version `0.1.0-SNAPSHOT`.

---

## Highlights

* **Data safety:** fixes for lost inserts, rollback bugs, stuck row locks and connections writing to another connection's database.
* **More SQL:** `OR`, `IN`, `LIKE`, `BETWEEN`, `LEFT JOIN`, `HAVING`, subqueries, constraints, `ALTER TABLE` and more.
* **Authentication:** an optional password on the SQL port, HTTP API and replication port.
* **Faster writes by key:** `UPDATE` and `DELETE` now use indexes. An update by primary key went from 18 ms to 2 ms on a table of 200,000 rows.
* **Safer failover:** replicas show only committed data, and promotion rolls back unfinished transactions.
* **Safer AI layer:** `ask()` is read-only by default, and generated SQL is checked against the schema.

---

## New features

### SQL

**Expressions**
* `OR`, `NOT`, `<>`, `IN`, `NOT IN`, `BETWEEN`, `LIKE`, `NOT LIKE`, `IS [NOT] NULL`
* `+ - * / %`, parentheses and unary minus, with SQL three-valued `NULL` logic
* Uncorrelated subqueries: scalar, `IN (SELECT ...)` and `EXISTS (SELECT ...)`
* Negative literals, the `NULL` literal and scientific notation (`1.5E3`)

**Queries**
* Table aliases, any number of `JOIN`s, and `LEFT [OUTER] JOIN`
* `SELECT DISTINCT`, `GROUP BY` on several columns, and `HAVING`
* `ORDER BY` an alias or aggregate, computed select items, and `SELECT` without `FROM`
* Double-quoted identifiers, such as `"order"` and `"Total Amount"`

**Changing data and schema**
* `INSERT` with column lists and several rows. If one row is bad, nothing is inserted.
* `UPDATE ... SET c = expression`, for example `age = age + 1`
* `NOT NULL`, `PRIMARY KEY` and `UNIQUE` constraints
* `VARCHAR(n)`: values longer than `n` characters are rejected on `INSERT` and `UPDATE`. Plain `VARCHAR` stays unlimited.
* `IF [NOT] EXISTS` for creating and dropping tables, databases and indexes
* `TRUNCATE [TABLE]`, which can be rolled back
* `ALTER TABLE t ADD [COLUMN]`, without rewriting the table
* `SHOW TABLES`, `SHOW DATABASES` and `DESCRIBE` in the core engine, not only over TCP
* Several statements in one SQL string
* Values larger than a page, stored out of line and included in crash recovery, replication and `VACUUM`

### Authentication
* Set a password with `--password`, `--password-file`, `KORA_PASSWORD` or `KORA_PASSWORD_FILE`. Set the user with `--user` or `KORA_USER` (default `kora`).
* When a password is set, it is required on the SQL port, the HTTP API (Basic or Bearer; `/v1/health` stays open for probes) and the replication port.
* A wrong password returns HTTP `401` or JDBC SQLState `28000`.
* The server logs a security warning if it listens beyond loopback without a password.
* The JDBC driver, CLI, docker-compose file and Helm chart (`auth.existingSecret`) all support it.

### JDBC driver
* `addBatch` / `executeBatch`, `setNull`, `setTimestamp` / `setDate`
* Transaction isolation mapping
* SQLState `40001` when the server aborts a transaction

### AI layer
* `ask()` only runs `SELECT`, `SHOW`, `DESCRIBE` and `EXPLAIN` by default. Writes need `AiExecutionPolicy.ALLOW_WRITES`, and DDL needs `ALLOW_ALL`.
* Generated SQL must be a single statement that uses tables and columns that exist.
* The offline translator understands the schema: synonyms, column values, several conditions, aggregates and superlatives. If it can't match a request, it says so instead of guessing a table.
* Provider failures come back as failed results instead of exceptions. Rate limits and server errors are retried with backoff.

### Tools
* `tools/manual-test/JdbcLoadTest.java` loads a large data set through the JDBC driver and checks the results against values computed in Java. It also has a mode for checking data after a `kill -9`.
* `tools/manual-test/tiny_bank.py` is a small web app for trying KoraDB by hand. It only needs Python.

---

## Performance

| Operation | Before | After |
|---|---|---|
| `UPDATE` by primary key (200,000 orders, 20,000 customers) | 18 ms | 2 ms |
| Transfers across 16 threads, same data set | 52 s | 2 s |
| Indexed lookup, with cached table statistics | about 280 ms | about 2 ms |

Nested-loop joins also read their inner side once instead of once per outer row.

---

## Bug fixes

### Storage and transactions
* Concurrent inserts could lose rows that had been acknowledged.
* `ROLLBACK` of a `DELETE` duplicated rows.
* Rows touched by a rolled-back or abandoned transaction stayed locked.
* A database could become permanently impossible to open.
* A rejected `UNIQUE` duplicate was still stored.
* All connections shared one current database, which could send writes to the wrong database and hang the server.
* A reader woken after a commit could miss the rows that had just been committed.

### Queries and indexes
* Lookups on non-unique indexes returned too few rows.
* `SELECT` by a `PRIMARY KEY` or `UNIQUE` column returned nothing after that row was updated.
* `WHERE id = 7.5` on an indexed column returned the row with `id = 7`.
* `VACUUM` left index entries behind for rows it had removed.

### Replication
* Replicas accepted writes through multi-statement strings, `TRUNCATE`, `ALTER TABLE` and HTTP `create=true`.
* Indexes, constraints and drops did not reach replicas. After a failover, the new primary had no indexes and accepted duplicate primary keys.
* A table with a column named after a keyword, such as `"order"`, was missing on replicas along with all its rows.
* Replicas showed changes from transactions still open on the primary, and promotion kept them, so half of a transfer could become permanent.
* A promoted node could lose committed work if it crashed.
* The server crashed on startup with `--data-dir` in the `PRIMARY` role.

### Server and clients
* A misspelled database name in the HTTP API created a new database.
* Query and transaction metrics in Prometheus never moved.
* JDBC `setTimestamp` failed for every timestamp with zero seconds.
* JDBC `ALTER TABLE` failed with `autoCommit=false`.
* The CLI garbled input and output on consoles using other character sets.

### Security
* A quoted name such as `"../x"` could reach outside the data directory. Names that become files may now only contain letters, digits, `_`, `-` and spaces.

---

## Behavior changes

These statements or calls worked before, or behaved differently, and may need changes in your application:

| Area | Now |
|---|---|
| DDL inside an open transaction | Rejected with an error, because DDL can't be rolled back |
| `SELECT` of a column that is neither grouped nor aggregated | Error |
| `ORDER BY` an unknown column | Error |
| Out-of-range or fractional values in `INT` / `BIGINT` | Error instead of silent truncation |
| `SUM` over integers | Returns `BIGINT`, with overflow detection |
| Comparing an indexed text column to a number (`name = 5`) | Type error, same as an unindexed column |
| HTTP API with an unknown database | Error, unless the statement is `CREATE` or the request sets `create=true` |
| AI `ask()` | Read-only unless you pass a policy that allows writes |
| Database, table and index names | Limited to letters, digits, `_`, `-` and spaces |
| Index keys | Limited to 1024 bytes |
| Protocol messages | Limited to 16 MB |
| Replication with a password set | Replicas must use the same password as the primary |

---

## Known limitations

* **No TLS.** Traffic isn't encrypted, so keep the ports on a private network or put a TLS proxy in front.
* **DDL isn't transactional.** It is rejected inside an open transaction.
* **Promoting a restarted replica when the primary is gone.** A replica keeps no WAL of its own, so it can't tell which rows are unfinished. Promotion logs a warning but can keep uncommitted data.
* **Slow replica catch-up.** A replica waits up to 60 seconds to catch up, then logs a warning and starts serving. Uncommitted rows can be visible until it has caught up.
* **Transactions open during promotion.** A transaction opened on a replica before promotion shouldn't be used for writes afterward.

---

## Upgrading

* Back up your data directory first. Opening a `v1.0.0` data directory with this release isn't covered by the automated tests.
* Check your application against [Behavior changes](#behavior-changes).
* If a node listens beyond `127.0.0.1`, set a password. It stays optional, and without one anyone on the network has full access.

---

## Testing

The release passes 613 automated tests on a clean build. They include a regression suite that runs against the real engine, TCP server, JDBC driver, HTTP API, replication and AI layer, and covers `kill -9` recovery and concurrent transfers. During development, SQL results were also compared with SQLite over 9,000 random queries.

---

## Install

```bash
docker run -d -p 8765:8765 -p 8080:8080 -p 9100:9100 \
  -e KORA_PASSWORD=change-me \
  -v koradb_data:/var/lib/koradb/data \
  ghcr.io/priyanshvaishnav/databasekora:2.0.1
```

* **Linux / macOS:** `curl -fsSL https://raw.githubusercontent.com/Priyanshvaishnav/DatabaseKora/main/install.sh | bash`
* **Windows (PowerShell):** `irm https://raw.githubusercontent.com/Priyanshvaishnav/DatabaseKora/main/install.ps1 | iex`

## Release assets

* `koradb-jdbc.jar`: JDBC driver
* `koradb-server.jar`: standalone server (`java -jar koradb-server.jar`)
* `koradb-cli.jar`: terminal client (`java -jar koradb-cli.jar`)
* `koradb-dist.tar.gz` and `koradb-dist.zip`: full distribution with launchers
* `koradb-v2.0.1-all.tar.gz` and `koradb-v2.0.1-all.zip`: the same distribution, with the version in the file name
* `SHA256SUMS.txt`: checksums for `koradb-dist.tar.gz` and `koradb-dist.zip`

---
**Full Changelog**: https://github.com/Priyanshvaishnav/DatabaseKora/compare/v1.0.0...v2.0.1
