# Manual load test (JDBC + database)

`JdbcLoadTest.java` loads a large, deterministic data set through the JDBC driver and checks every
answer the database gives against values it computes itself in Java. A run prints `PASS`/`FAIL`
per check and exits non-zero on any failure.

What it checks, in order:

| Phase | What happens |
|---|---|
| 1. Load | Customers through `PreparedStatement` batches (int, varchar, double, boolean, timestamp), orders through multi-row `INSERT`s, in transactions of `--batch` rows |
| 2. Verify | `COUNT`, `SUM`, `MIN`, `GROUP BY` (incl. `O'Hare` and `Zürich`), `JOIN`, `JOIN + WHERE + SUM`, `ORDER BY ... LIMIT`, typed `ResultSet` getters |
| 3. Index | `CREATE INDEX`, 200 indexed lookups including the heaviest duplicate keys, `EXPLAIN` uses the index, then all of phase 2 again |
| 4. DML | `DELETE` and `UPDATE` affected-row counts, then counts, sums and groups after the changes |
| 5. Rollback | Delete most rows, zero every balance, insert a row, check the transaction sees that, `rollback()`, check everything is back |
| 6. Concurrency | `--threads` connections transfer money between 50 accounts, retrying on write conflicts; every transfer must commit and the total must not change |
| Final | The state the database must be in after all of the above, including the updated accounts read back by primary key |

## 1. Build and start a server

```bash
mvn package -DskipTests
java -jar kora-server/target/koradb-server.jar --role STANDALONE --data-dir ./loadtest-data --password test123
```

## 2. Run the test (from another terminal)

```bash
java -cp kora-jdbc/target/koradb-jdbc.jar tools/manual-test/JdbcLoadTest.java --url jdbc:koradb://127.0.0.1:8765/loadtest --user kora --password test123 --customers 20000 --orders 200000 --threads 16
```

Options: `--customers`, `--orders`, `--threads`, `--batch` (rows per transaction, default 500),
`--seed` (default 42). Each run drops and recreates its two tables in the database named in the URL.

## 3. Crash test

1. Run step 2 to the end.
2. Kill the server hard: `taskkill /F /PID <pid>` on Windows (find it with `netstat -ano | findstr 8765`), `kill -9 <pid>` on Linux/macOS.
3. Start it again with the same `--data-dir`.
4. Run step 2 again with `--verify-only` and the same `--customers`, `--orders` and `--seed`. It checks the final state without changing anything.

To test a crash mid-load, kill the server during phase 1 instead, restart, and check with the CLI that
the row count is a whole multiple of `--batch` (only committed batches survive):

```bash
java -jar kora-cli/target/koradb-cli.jar -u kora -d loadtest -e "SELECT COUNT(*) FROM orders"
```

(set `KORA_PASSWORD=test123` first).

## Measured on this machine (JDK 25, 2026-10-30)

20,000 customers / 200,000 orders: 45/45 checks pass; customers load at ~10,000 rows/s, orders at ~55,000 rows/s,
`CREATE INDEX` on 200k rows 3 s, indexed lookup 0.5 ms, full-table `COUNT`/`SUM` 0.3–0.5 s,
200k x 20k `JOIN` 1–1.3 s, `UPDATE ... WHERE id = ?` 2 ms, concurrency phase 2 s.
Kill + restart + `--verify-only`: 7/7 pass.
