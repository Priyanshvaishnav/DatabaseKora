package dev.koradb.storage.recovery;

import dev.koradb.KoraDB;
import dev.koradb.execution.QueryResult;
import dev.koradb.session.Session;
import dev.koradb.storage.StorageConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Milestone 9 — ARIES Crash Recovery Test Suite")
class CrashRecoveryTest {

    @TempDir
    Path dataDir;

    @Test
    @DisplayName("Redo pass recovers committed updates missing from disk file")
    void redoRecoversCommittedUpdates() throws IOException {
        Path dbDir = dataDir.resolve("shop");
        Path tablePath = StorageConfig.tableFilePath(dbDir, "products");
        Path backupTablePath = dataDir.resolve("products_backup.kdb");

        // 1. Initial database setup with 1 row
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE shop");
            db.execute("USE shop");
            db.execute("CREATE TABLE products (id INT, name VARCHAR, price DOUBLE)");
            db.execute("INSERT INTO products VALUES (1, 'Laptop', 1200.0)");
        }

        // 2. Backup products.kdb containing only row 1
        Files.copy(tablePath, backupTablePath, StandardCopyOption.REPLACE_EXISTING);

        // 3. Insert row 2 in a transaction and commit
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("USE shop");
            Session session = db.createSession();
            db.execute(session, "BEGIN");
            db.execute(session, "INSERT INTO products VALUES (2, 'Phone', 800.0)");
            db.execute(session, "COMMIT");
        }

        // 4. Simulate crash where page for row 2 was not flushed to disk:
        // Overwrite products.kdb with backup containing only row 1
        Files.copy(backupTablePath, tablePath, StandardCopyOption.REPLACE_EXISTING);

        // 5. Open new database instance -> triggers ARIES recovery
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("USE shop");
            QueryResult result = db.execute("SELECT * FROM products ORDER BY id");

            // Redo pass should have reapplied row 2!
            assertThat(result.rows()).hasSize(2);
            assertThat(result.rows().get(0).get("name")).isEqualTo("Laptop");
            assertThat(result.rows().get(1).get("name")).isEqualTo("Phone");
        }
    }

    @Test
    @DisplayName("Undo pass rolls back uncommitted loser transactions")
    void undoRollsBackUncommittedLoser() {
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE shop");
            db.execute("USE shop");
            db.execute("CREATE TABLE products (id INT, name VARCHAR, price DOUBLE)");
            db.execute("INSERT INTO products VALUES (1, 'Laptop', 1200.0)");

            // Start uncommitted transaction
            Session session = db.createSession();
            db.execute(session, "BEGIN");
            db.execute(session, "INSERT INTO products VALUES (2, 'UncommittedPhone', 500.0)");

            // Flush WAL so the UPDATE record is persisted before the crash
            dev.koradb.storage.wal.LogManager lm = db.getCatalog().getLogManager();
            assertThat(lm).isNotNull();
            lm.flush(lm.getNextLsn());

            // Simulate crash: do NOT commit or rollback session, do not cleanly close DB
        }

        // Restart and recover
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("USE shop");
            QueryResult result = db.execute("SELECT * FROM products");

            // Row 2 must have been undone!
            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().get(0).get("name")).isEqualTo("Laptop");
        }
    }

    @Test
    @DisplayName("Mixed winners and losers: winners retained, losers rolled back")
    void mixedWinnersAndLosers() {
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE bank");
            db.execute("USE bank");
            db.execute("CREATE TABLE accounts (id INT, name VARCHAR, balance DOUBLE)");
            db.execute("CREATE TABLE audit (id INT, msg VARCHAR)");

            // Winner transaction 1 on accounts
            Session s1 = db.createSession();
            db.execute(s1, "BEGIN");
            db.execute(s1, "INSERT INTO accounts VALUES (1, 'Alice', 1000.0)");
            db.execute(s1, "COMMIT");

            // Loser transaction 2 on accounts (uncommitted)
            Session s2 = db.createSession();
            db.execute(s2, "BEGIN");
            db.execute(s2, "INSERT INTO accounts VALUES (2, 'Bob_Uncommitted', 2000.0)");

            // Winner transaction 3 on audit table (concurrently active, commits)
            Session s3 = db.createSession();
            db.execute(s3, "BEGIN");
            db.execute(s3, "INSERT INTO audit VALUES (10, 'Audit record 10')");
            db.execute(s3, "COMMIT");

            // Flush WAL
            dev.koradb.storage.wal.LogManager lm = db.getCatalog().getLogManager();
            lm.flush(lm.getNextLsn());
        }

        // Restart and recover
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("USE bank");
            QueryResult accountsResult = db.execute("SELECT * FROM accounts ORDER BY id");
            QueryResult auditResult = db.execute("SELECT * FROM audit");

            // Winner on accounts retained, loser on accounts rolled back
            assertThat(accountsResult.rows()).hasSize(1);
            assertThat(accountsResult.rows().get(0).get("name")).isEqualTo("Alice");

            // Winner on audit retained
            assertThat(auditResult.rows()).hasSize(1);
            assertThat(auditResult.rows().get(0).get("msg")).isEqualTo("Audit record 10");
        }
    }

    @Test
    @DisplayName("CLR idempotency prevents duplicate undo during repeated crashes")
    void clrIdempotencyDuringRepeatedRecovery() {
        Path dbDir = dataDir.resolve("orders");

        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE orders");
            db.execute("USE orders");
            db.execute("CREATE TABLE orders (id INT, item VARCHAR)");

            // Uncommitted transaction with multiple inserts
            Session s = db.createSession();
            db.execute(s, "BEGIN");
            db.execute(s, "INSERT INTO orders VALUES (101, 'Book')");
            db.execute(s, "INSERT INTO orders VALUES (102, 'Pen')");

            dev.koradb.storage.wal.LogManager lm = db.getCatalog().getLogManager();
            lm.flush(lm.getNextLsn());
        }

        // Recovery run 1
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("USE orders");
            QueryResult result = db.execute("SELECT * FROM orders");
            assertThat(result.rows()).isEmpty();
        }

        // Recovery run 2 (simulating restart after recovery completed)
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("USE orders");
            QueryResult result = db.execute("SELECT * FROM orders");
            assertThat(result.rows()).isEmpty();
        }
    }

    @Test
    @DisplayName("Crash recovery starts from checkpoint and skips already flushed records")
    void recoveryWithCheckpoint() throws IOException {
        Path dbDir = dataDir.resolve("warehouse");
        Path tablePath = StorageConfig.tableFilePath(dbDir, "items");
        Path cpBackupPath = dataDir.resolve("items_checkpoint.kdb");

        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE warehouse");
            db.execute("USE warehouse");
            db.execute("CREATE TABLE items (id INT, name VARCHAR)");
            db.execute("INSERT INTO items VALUES (1, 'Widget')");

            // Flush and Checkpoint
            db.checkpoint();
        }

        // Backup at checkpoint time
        Files.copy(tablePath, cpBackupPath, StandardCopyOption.REPLACE_EXISTING);

        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("USE warehouse");

            // Winner transaction after checkpoint
            Session s1 = db.createSession();
            db.execute(s1, "BEGIN");
            db.execute(s1, "INSERT INTO items VALUES (2, 'Gadget')");
            db.execute(s1, "COMMIT");

            // Loser transaction after checkpoint
            Session s2 = db.createSession();
            db.execute(s2, "BEGIN");
            db.execute(s2, "INSERT INTO items VALUES (3, 'Doohickey_Uncommitted')");

            dev.koradb.storage.wal.LogManager lm = db.getCatalog().getLogManager();
            lm.flush(lm.getNextLsn());
        }

        // Revert table file to checkpoint state
        Files.copy(cpBackupPath, tablePath, StandardCopyOption.REPLACE_EXISTING);

        // Restart and recover
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("USE warehouse");
            QueryResult result = db.execute("SELECT * FROM items ORDER BY id");

            assertThat(result.rows()).hasSize(2);
            assertThat(result.rows().get(0).get("name")).isEqualTo("Widget");
            assertThat(result.rows().get(1).get("name")).isEqualTo("Gadget");
        }
    }

    @Test
    @DisplayName("B+ Tree index rebuilds properly after recovery modifications")
    void indexRebuiltAfterRecovery() {
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("CREATE DATABASE store");
            db.execute("USE store");
            db.execute("CREATE TABLE inventory (id INT, sku VARCHAR, qty INT)");
            db.execute("CREATE INDEX idx_id ON inventory(id)");

            // Committed row
            db.execute("INSERT INTO inventory VALUES (1, 'SKU-001', 10)");

            // Uncommitted row
            Session s = db.createSession();
            db.execute(s, "BEGIN");
            db.execute(s, "INSERT INTO inventory VALUES (2, 'SKU-002', 20)");

            dev.koradb.storage.wal.LogManager lm = db.getCatalog().getLogManager();
            lm.flush(lm.getNextLsn());
        }

        // Restart and recover
        try (KoraDB db = new KoraDB(dataDir)) {
            db.execute("USE store");

            // Index point query
            QueryResult q1 = db.execute("SELECT * FROM inventory WHERE id = 1");
            assertThat(q1.rows()).hasSize(1);
            assertThat(q1.rows().get(0).get("sku")).isEqualTo("SKU-001");

            // Point query for undone row should return nothing
            QueryResult q2 = db.execute("SELECT * FROM inventory WHERE id = 2");
            assertThat(q2.rows()).isEmpty();
        }
    }
}
