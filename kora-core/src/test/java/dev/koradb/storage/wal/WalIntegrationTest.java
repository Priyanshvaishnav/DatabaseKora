package dev.koradb.storage.wal;

import dev.koradb.KoraDB;
import dev.koradb.execution.QueryResult;
import dev.koradb.session.Session;
import dev.koradb.storage.StorageConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WAL Integration Tests")
class WalIntegrationTest {

    @Test
    @DisplayName("End-to-end transaction logging records BEGIN, UPDATE, and COMMIT to WAL")
    void endToEndTransactionLogging(@TempDir Path tempDir) {
        Path walPath;
        try (KoraDB db = new KoraDB(tempDir)) {
            db.execute("CREATE DATABASE shop");
            db.execute("USE shop");
            db.execute("CREATE TABLE products (id INT, name VARCHAR, price DOUBLE)");

            db.execute("BEGIN");
            db.execute("INSERT INTO products VALUES (1, 'Laptop', 1200.0)");
            db.execute("UPDATE products SET price = 1100.0 WHERE id = 1");
            db.execute("COMMIT");

            walPath = StorageConfig.walFilePath(tempDir.resolve("shop"));
            assertThat(Files.exists(walPath)).isTrue();
        }

        // Iterate through WAL file and verify logged records
        List<LogRecord> records = new ArrayList<>();
        try (LogIterator it = new LogIterator(walPath)) {
            while (it.hasNext()) {
                records.add(it.next());
            }
        }

        assertThat(records).isNotEmpty();

        // Find the explicit transaction's COMMIT record (the last commit)
        CommitLogRecord commitRecord = records.stream()
                .filter(r -> r.getType() == LogRecordType.COMMIT)
                .reduce((first, second) -> second)
                .map(r -> (CommitLogRecord) r)
                .orElseThrow();
        long txnId = commitRecord.getTxnId();
        assertThat(txnId).isGreaterThan(0L);

        // Find the matching BEGIN record
        BeginLogRecord beginRecord = records.stream()
                .filter(r -> r.getType() == LogRecordType.BEGIN && r.getTxnId() == txnId)
                .map(r -> (BeginLogRecord) r)
                .findFirst()
                .orElseThrow();

        // Verify update records between BEGIN and COMMIT
        List<UpdateLogRecord> updates = records.stream()
                .filter(r -> r.getType() == LogRecordType.UPDATE && r.getTxnId() == txnId)
                .map(r -> (UpdateLogRecord) r)
                .toList();

        // Insert + Update (which is delete + re-insert) -> at least 3 update records
        assertThat(updates).hasSizeGreaterThanOrEqualTo(3);
        for (UpdateLogRecord u : updates) {
            assertThat(u.getTableName()).isEqualTo("products");
        }

        // Verify LSN chaining (Commit prevLSN points to the last update LSN)
        UpdateLogRecord lastUpdate = updates.get(updates.size() - 1);
        assertThat(commitRecord.getPrevLsn()).isEqualTo(lastUpdate.getLsn());
    }

    @Test
    @DisplayName("Rollback logs ABORT record and reverses state")
    void rollbackLogging(@TempDir Path tempDir) {
        Path walPath;
        try (KoraDB db = new KoraDB(tempDir)) {
            db.execute("CREATE DATABASE bank");
            db.execute("USE bank");
            db.execute("CREATE TABLE accounts (id INT, owner VARCHAR, balance DOUBLE)");

            db.execute("BEGIN");
            db.execute("INSERT INTO accounts VALUES (10, 'Bob', 500.0)");
            db.execute("ROLLBACK");

            walPath = StorageConfig.walFilePath(tempDir.resolve("bank"));

            // Verify row is not in accounts
            QueryResult res = db.execute("SELECT * FROM accounts");
            assertThat(res.rows()).isEmpty();
        }

        List<LogRecord> records = new ArrayList<>();
        try (LogIterator it = new LogIterator(walPath)) {
            while (it.hasNext()) {
                records.add(it.next());
            }
        }

        // Verify presence of BEGIN, UPDATE, and ABORT
        assertThat(records.stream().anyMatch(r -> r.getType() == LogRecordType.BEGIN)).isTrue();
        assertThat(records.stream().anyMatch(r -> r.getType() == LogRecordType.UPDATE)).isTrue();
        assertThat(records.stream().anyMatch(r -> r.getType() == LogRecordType.ABORT)).isTrue();
    }

    @Test
    @DisplayName("Autocommit statements are wrapped in transactions and logged to WAL")
    void autocommitStatementsLogged(@TempDir Path tempDir) {
        Path walPath;
        try (KoraDB db = new KoraDB(tempDir)) {
            db.execute("CREATE DATABASE inventory");
            db.execute("USE inventory");
            db.execute("CREATE TABLE items (id INT, qty INT)");

            // Autocommit insert
            db.execute("INSERT INTO items VALUES (1, 50)");

            walPath = StorageConfig.walFilePath(tempDir.resolve("inventory"));
        }

        List<LogRecord> records = new ArrayList<>();
        try (LogIterator it = new LogIterator(walPath)) {
            while (it.hasNext()) {
                records.add(it.next());
            }
        }

        assertThat(records.stream().anyMatch(r -> r.getType() == LogRecordType.BEGIN)).isTrue();
        assertThat(records.stream().anyMatch(r -> r.getType() == LogRecordType.UPDATE)).isTrue();
        assertThat(records.stream().anyMatch(r -> r.getType() == LogRecordType.COMMIT)).isTrue();
    }

    @Test
    @DisplayName("Checkpoint captures active transactions and dirty pages")
    void checkpointCapturesState(@TempDir Path tempDir) {
        Path walPath;
        try (KoraDB db = new KoraDB(tempDir)) {
            db.execute("CREATE DATABASE corp");
            db.execute("USE corp");
            db.execute("CREATE TABLE staff (id INT, name VARCHAR)");

            // Start an in-flight transaction
            Session session = db.createSession();
            db.execute(session, "BEGIN");
            db.execute(session, "INSERT INTO staff VALUES (1, 'Alice')");

            // Execute checkpoint while transaction is in-flight
            long cpLsn = db.checkpoint();
            assertThat(cpLsn).isGreaterThan(0L);

            // Complete the in-flight transaction
            db.execute(session, "COMMIT");

            walPath = StorageConfig.walFilePath(tempDir.resolve("corp"));
        }

        List<LogRecord> records = new ArrayList<>();
        try (LogIterator it = new LogIterator(walPath)) {
            while (it.hasNext()) {
                records.add(it.next());
            }
        }

        CheckpointBeginRecord cpBegin = records.stream()
                .filter(r -> r.getType() == LogRecordType.CHECKPOINT_BEGIN)
                .map(r -> (CheckpointBeginRecord) r)
                .findFirst()
                .orElseThrow();
        assertThat(cpBegin.getTimestamp()).isGreaterThan(0L);

        CheckpointEndRecord cpEnd = records.stream()
                .filter(r -> r.getType() == LogRecordType.CHECKPOINT_END)
                .map(r -> (CheckpointEndRecord) r)
                .findFirst()
                .orElseThrow();

        // In-flight transaction was active during checkpoint
        assertThat(cpEnd.getActiveTransactionTable()).isNotEmpty();
    }

    @Test
    @DisplayName("WAL survives database restart and continues appending monotonically")
    void walSurvivesRestart(@TempDir Path tempDir) {
        long firstCommitLsn;

        try (KoraDB db = new KoraDB(tempDir)) {
            db.execute("CREATE DATABASE testdb");
            db.execute("USE testdb");
            db.execute("CREATE TABLE t (id INT)");
            db.execute("INSERT INTO t VALUES (10)");
        }

        Path walPath = StorageConfig.walFilePath(tempDir.resolve("testdb"));
        long walSizeBeforeRestart;
        try {
            walSizeBeforeRestart = Files.size(walPath);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Reopen database
        try (KoraDB db = new KoraDB(tempDir)) {
            db.execute("USE testdb");
            db.execute("INSERT INTO t VALUES (20)");

            QueryResult res = db.execute("SELECT * FROM t");
            assertThat(res.rows()).hasSize(2);
        }

        long walSizeAfterRestart;
        try {
            walSizeAfterRestart = Files.size(walPath);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // WAL file grew monotonically
        assertThat(walSizeAfterRestart).isGreaterThan(walSizeBeforeRestart);

        // Verify all records in WAL file can be cleanly iterated
        List<LogRecord> records = new ArrayList<>();
        try (LogIterator it = new LogIterator(walPath)) {
            while (it.hasNext()) {
                records.add(it.next());
            }
        }
        assertThat(records.size()).isGreaterThanOrEqualTo(4);
    }
}
